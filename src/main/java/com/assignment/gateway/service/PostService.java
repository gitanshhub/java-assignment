package com.assignment.gateway.service;

import com.assignment.gateway.dto.CommentResponse;
import com.assignment.gateway.dto.CreateCommentRequest;
import com.assignment.gateway.dto.CreatePostRequest;
import com.assignment.gateway.dto.InteractionResponse;
import com.assignment.gateway.dto.PostResponse;
import com.assignment.gateway.entity.ActorType;
import com.assignment.gateway.entity.Bot;
import com.assignment.gateway.entity.Comment;
import com.assignment.gateway.entity.Post;
import com.assignment.gateway.repository.BotRepository;
import com.assignment.gateway.repository.CommentRepository;
import com.assignment.gateway.repository.PostRepository;
import com.assignment.gateway.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);
    private static final int MAX_COMMENT_DEPTH = 20;
    private static final int MAX_BOT_REPLIES_PER_POST = 100;

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final BotRepository botRepository;
    private final GuardrailStore guardrailStore;

    public PostService(
            PostRepository postRepository,
            CommentRepository commentRepository,
            UserRepository userRepository,
            BotRepository botRepository,
            GuardrailStore guardrailStore
    ) {
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.botRepository = botRepository;
        this.guardrailStore = guardrailStore;
    }

    @Transactional
    public PostResponse createPost(CreatePostRequest request) {
        validateActorExists(request.getAuthorType(), request.getAuthorId());

        Post post = new Post();
        post.setAuthorType(request.getAuthorType());
        post.setAuthorId(request.getAuthorId());
        post.setContent(request.getContent());

        Post saved = postRepository.save(post);
        return new PostResponse(
                saved.getId(),
                saved.getAuthorType(),
                saved.getAuthorId(),
                saved.getContent(),
                saved.getCreatedAt(),
                guardrailStore.getViralityScore(saved.getId())
        );
    }

    @Transactional
    public CommentResponse addComment(Long postId, CreateCommentRequest request) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
        validateActorExists(request.getAuthorType(), request.getAuthorId());

        Comment parent = null;
        int depthLevel = 0;
        if (request.getParentCommentId() != null) {
            parent = commentRepository.findById(request.getParentCommentId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent comment not found"));

            if (!parent.getPost().getId().equals(postId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent comment does not belong to the post");
            }
            depthLevel = parent.getDepthLevel() + 1;
        }

        if (depthLevel > MAX_COMMENT_DEPTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vertical cap exceeded");
        }

        if (request.getAuthorType() == ActorType.BOT) {
            long currentCount = guardrailStore.reserveBotReplySlot(postId);
            boolean slotHeld = true;
            try {
                if (currentCount > MAX_BOT_REPLIES_PER_POST) {
                    guardrailStore.releaseBotReplySlot(postId);
                    slotHeld = false;
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Horizontal bot reply cap exceeded");
                }
                registerBotSlotRollback(postId);

                Long targetHumanId = resolveTargetHumanId(post, parent);
                if (targetHumanId != null && !guardrailStore.activateBotHumanCooldown(request.getAuthorId(), targetHumanId)) {
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Bot-human cooldown cap active");
                }
            } catch (RuntimeException ex) {
                if (slotHeld && !TransactionSynchronizationManager.isSynchronizationActive()) {
                    guardrailStore.releaseBotReplySlot(postId);
                }
                throw ex;
            }
        }

        Comment comment = new Comment();
        comment.setPost(post);
        comment.setParentComment(parent);
        comment.setAuthorType(request.getAuthorType());
        comment.setAuthorId(request.getAuthorId());
        comment.setContent(request.getContent());
        comment.setDepthLevel(depthLevel);

        Comment saved = commentRepository.save(comment);

        InteractionType interactionType = request.getAuthorType() == ActorType.BOT
                ? InteractionType.BOT_REPLY
                : InteractionType.HUMAN_COMMENT;
        long viralityScore = guardrailStore.incrementViralityScore(postId, interactionType);

        if (request.getAuthorType() == ActorType.BOT && post.getAuthorType() == ActorType.USER) {
            Bot bot = botRepository.findById(request.getAuthorId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot not found"));
            handleNotification(post.getAuthorId(), bot.getName());
        }

        return new CommentResponse(
                saved.getId(),
                postId,
                parent == null ? null : parent.getId(),
                saved.getAuthorType(),
                saved.getAuthorId(),
                saved.getContent(),
                saved.getDepthLevel(),
                saved.getCreatedAt(),
                viralityScore
        );
    }

    @Transactional(readOnly = true)
    public InteractionResponse likePost(Long postId, Long userId) {
        postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));
        userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        long viralityScore = guardrailStore.incrementViralityScore(postId, InteractionType.HUMAN_LIKE);
        return new InteractionResponse(postId, "Post liked", viralityScore);
    }

    private void validateActorExists(ActorType actorType, Long authorId) {
        boolean exists = switch (actorType) {
            case USER -> userRepository.existsById(authorId);
            case BOT -> botRepository.existsById(authorId);
        };

        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, actorType + " not found");
        }
    }

    private Long resolveTargetHumanId(Post post, Comment parent) {
        if (parent != null && parent.getAuthorType() == ActorType.USER) {
            return parent.getAuthorId();
        }
        if (post.getAuthorType() == ActorType.USER) {
            return post.getAuthorId();
        }
        return null;
    }

    private void registerBotSlotRollback(Long postId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    guardrailStore.releaseBotReplySlot(postId);
                }
            }
        });
    }

    private void handleNotification(Long userId, String botName) {
        String message = "Bot %s replied to your post".formatted(botName);
        if (guardrailStore.hasRecentNotificationCooldown(userId)) {
            guardrailStore.queuePendingNotification(userId, message);
            return;
        }

        log.info("Push Notification Sent to User {}: {}", userId, message);
        guardrailStore.markNotificationCooldown(userId);
    }
}

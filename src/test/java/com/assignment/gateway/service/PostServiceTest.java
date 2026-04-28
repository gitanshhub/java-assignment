package com.assignment.gateway.service;

import com.assignment.gateway.dto.CreateCommentRequest;
import com.assignment.gateway.entity.ActorType;
import com.assignment.gateway.entity.Bot;
import com.assignment.gateway.entity.Comment;
import com.assignment.gateway.entity.Post;
import com.assignment.gateway.repository.BotRepository;
import com.assignment.gateway.repository.CommentRepository;
import com.assignment.gateway.repository.PostRepository;
import com.assignment.gateway.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock
    private PostRepository postRepository;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private BotRepository botRepository;

    private FakeGuardrailStore guardrailStore;
    private PostService postService;

    @BeforeEach
    void setUp() {
        guardrailStore = new FakeGuardrailStore();
        postService = new PostService(postRepository, commentRepository, userRepository, botRepository, guardrailStore);
    }

    @Test
    void shouldStopExactlyAtHundredBotRepliesDuringConcurrentSpam() throws Exception {
        Long postId = 1L;
        Post post = post(postId, ActorType.BOT, 999L);
        AtomicLong commentIdSequence = new AtomicLong(0);
        AtomicInteger persistedComments = new AtomicInteger(0);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(botRepository.existsById(any(Long.class))).thenReturn(true);
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> {
            Comment comment = invocation.getArgument(0);
            comment.setId(commentIdSequence.incrementAndGet());
            persistedComments.incrementAndGet();
            return comment;
        });

        int totalRequests = 200;
        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch ready = new CountDownLatch(totalRequests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatusCode>> futures = new ArrayList<>();

        for (int i = 0; i < totalRequests; i++) {
            long botId = i + 1L;
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(15, TimeUnit.SECONDS));

                try {
                    postService.addComment(postId, botComment(botId, "bot-" + botId));
                    return HttpStatus.CREATED;
                } catch (ResponseStatusException ex) {
                    return ex.getStatusCode();
                }
            }));
        }

        assertTrue(ready.await(15, TimeUnit.SECONDS));
        start.countDown();

        int successCount = 0;
        int throttledCount = 0;
        for (Future<HttpStatusCode> future : futures) {
            HttpStatusCode status = future.get(20, TimeUnit.SECONDS);
            if (status == HttpStatus.CREATED) {
                successCount++;
            } else if (status == HttpStatus.TOO_MANY_REQUESTS) {
                throttledCount++;
            }
        }

        executor.shutdownNow();

        assertEquals(100, successCount);
        assertEquals(100, throttledCount);
        assertEquals(100, persistedComments.get());
        assertEquals(100L, guardrailStore.getBotReplyCount(postId));
        assertEquals(100L, guardrailStore.getViralityScore(postId));
    }

    @Test
    void shouldRejectCommentWhenDepthExceedsVerticalCap() {
        Long postId = 11L;
        Post post = post(postId, ActorType.USER, 1L);
        Comment parent = new Comment();
        parent.setId(77L);
        parent.setPost(post);
        parent.setAuthorType(ActorType.USER);
        parent.setAuthorId(1L);
        parent.setDepthLevel(20);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(userRepository.existsById(2L)).thenReturn(true);
        when(commentRepository.findById(77L)).thenReturn(Optional.of(parent));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> postService.addComment(postId, humanReply(2L, 77L, "too deep")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(commentRepository, never()).save(any(Comment.class));
    }

    @Test
    void shouldRejectSecondBotInteractionDuringCooldownAndReleaseReservedSlot() {
        Long postId = 21L;
        Post post = post(postId, ActorType.USER, 1L);
        AtomicLong commentIdSequence = new AtomicLong(0);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(botRepository.existsById(5L)).thenReturn(true);
        when(botRepository.findById(5L)).thenReturn(Optional.of(bot(5L, "Nova")));
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> {
            Comment comment = invocation.getArgument(0);
            comment.setId(commentIdSequence.incrementAndGet());
            return comment;
        });

        postService.addComment(postId, botComment(5L, "first"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> postService.addComment(postId, botComment(5L, "second")));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        assertEquals(1L, guardrailStore.getBotReplyCount(postId));
        verify(commentRepository, times(1)).save(any(Comment.class));
    }

    @Test
    void shouldQueuePendingNotificationWhenUserIsAlreadyInNotificationCooldown() {
        Long postId = 31L;
        Post post = post(postId, ActorType.USER, 1L);
        AtomicLong commentIdSequence = new AtomicLong(0);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(botRepository.existsById(7L)).thenReturn(true);
        when(botRepository.existsById(8L)).thenReturn(true);
        when(botRepository.findById(7L)).thenReturn(Optional.of(bot(7L, "Nova")));
        when(botRepository.findById(8L)).thenReturn(Optional.of(bot(8L, "Atlas")));
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> {
            Comment comment = invocation.getArgument(0);
            comment.setId(commentIdSequence.incrementAndGet());
            return comment;
        });

        postService.addComment(postId, botComment(7L, "first"));
        postService.addComment(postId, botComment(8L, "second"));

        assertTrue(guardrailStore.hasRecentNotificationCooldown(1L));
        assertEquals(1, guardrailStore.pendingNotificationCount(1L));
    }

    private Post post(Long id, ActorType authorType, Long authorId) {
        Post post = new Post();
        post.setId(id);
        post.setAuthorType(authorType);
        post.setAuthorId(authorId);
        post.setContent("post");
        return post;
    }

    private Bot bot(Long id, String name) {
        Bot bot = new Bot();
        bot.setId(id);
        bot.setName(name);
        bot.setPersonaDescription(name + " persona");
        return bot;
    }

    private CreateCommentRequest botComment(Long botId, String content) {
        CreateCommentRequest request = new CreateCommentRequest();
        request.setAuthorType(ActorType.BOT);
        request.setAuthorId(botId);
        request.setContent(content);
        return request;
    }

    private CreateCommentRequest humanReply(Long userId, Long parentCommentId, String content) {
        CreateCommentRequest request = new CreateCommentRequest();
        request.setAuthorType(ActorType.USER);
        request.setAuthorId(userId);
        request.setParentCommentId(parentCommentId);
        request.setContent(content);
        return request;
    }

    private static final class FakeGuardrailStore implements GuardrailStore {

        private final ConcurrentHashMap<Long, AtomicLong> viralityScores = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, AtomicLong> botCounts = new ConcurrentHashMap<>();
        private final Set<String> cooldownKeys = ConcurrentHashMap.newKeySet();
        private final Set<Long> notificationCooldownUsers = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<Long, List<String>> pendingNotifications = new ConcurrentHashMap<>();

        @Override
        public long incrementViralityScore(Long postId, InteractionType interactionType) {
            return viralityScores.computeIfAbsent(postId, ignored -> new AtomicLong())
                    .addAndGet(interactionType.getPoints());
        }

        @Override
        public long getViralityScore(Long postId) {
            return viralityScores.getOrDefault(postId, new AtomicLong()).get();
        }

        @Override
        public long reserveBotReplySlot(Long postId) {
            return botCounts.computeIfAbsent(postId, ignored -> new AtomicLong()).incrementAndGet();
        }

        @Override
        public void releaseBotReplySlot(Long postId) {
            botCounts.computeIfAbsent(postId, ignored -> new AtomicLong()).decrementAndGet();
        }

        @Override
        public boolean activateBotHumanCooldown(Long botId, Long humanId) {
            return cooldownKeys.add(botId + ":" + humanId);
        }

        @Override
        public boolean hasRecentNotificationCooldown(Long userId) {
            return notificationCooldownUsers.contains(userId);
        }

        @Override
        public void markNotificationCooldown(Long userId) {
            notificationCooldownUsers.add(userId);
        }

        @Override
        public void queuePendingNotification(Long userId, String message) {
            pendingNotifications.computeIfAbsent(userId,
                    ignored -> Collections.synchronizedList(new ArrayList<>())).add(message);
        }

        @Override
        public Set<String> getPendingNotificationUsers() {
            return pendingNotifications.keySet().stream()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public List<String> drainPendingNotifications(Long userId) {
            List<String> drained = pendingNotifications.remove(userId);
            return drained == null ? Collections.emptyList() : List.copyOf(drained);
        }

        long getBotReplyCount(Long postId) {
            return botCounts.getOrDefault(postId, new AtomicLong()).get();
        }

        int pendingNotificationCount(Long userId) {
            List<String> notifications = pendingNotifications.get(userId);
            return notifications == null ? 0 : notifications.size();
        }
    }
}

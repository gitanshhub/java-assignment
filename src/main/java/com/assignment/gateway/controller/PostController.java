package com.assignment.gateway.controller;

import com.assignment.gateway.dto.CommentResponse;
import com.assignment.gateway.dto.CreateCommentRequest;
import com.assignment.gateway.dto.CreatePostRequest;
import com.assignment.gateway.dto.InteractionResponse;
import com.assignment.gateway.dto.LikePostRequest;
import com.assignment.gateway.dto.PostResponse;
import com.assignment.gateway.service.PostService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PostResponse createPost(@Valid @RequestBody CreatePostRequest request) {
        return postService.createPost(request);
    }

    @PostMapping("/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse addComment(
            @PathVariable Long postId,
            @Valid @RequestBody CreateCommentRequest request
    ) {
        return postService.addComment(postId, request);
    }

    @PostMapping("/{postId}/like")
    public InteractionResponse likePost(@PathVariable Long postId, @Valid @RequestBody LikePostRequest request) {
        return postService.likePost(postId, request.getUserId());
    }
}

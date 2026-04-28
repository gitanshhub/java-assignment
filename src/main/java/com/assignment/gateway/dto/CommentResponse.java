package com.assignment.gateway.dto;

import com.assignment.gateway.entity.ActorType;

import java.time.Instant;

public record CommentResponse(
        Long id,
        Long postId,
        Long parentCommentId,
        ActorType authorType,
        Long authorId,
        String content,
        int depthLevel,
        Instant createdAt,
        long viralityScore
) {
}

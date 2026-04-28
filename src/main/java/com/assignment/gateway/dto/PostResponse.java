package com.assignment.gateway.dto;

import com.assignment.gateway.entity.ActorType;

import java.time.Instant;

public record PostResponse(
        Long id,
        ActorType authorType,
        Long authorId,
        String content,
        Instant createdAt,
        long viralityScore
) {
}

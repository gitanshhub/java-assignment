package com.assignment.gateway.dto;

public record InteractionResponse(
        Long postId,
        String message,
        long viralityScore
) {
}

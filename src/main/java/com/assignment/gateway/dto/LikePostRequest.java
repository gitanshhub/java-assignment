package com.assignment.gateway.dto;

import jakarta.validation.constraints.NotNull;

public class LikePostRequest {

    @NotNull
    private Long userId;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}

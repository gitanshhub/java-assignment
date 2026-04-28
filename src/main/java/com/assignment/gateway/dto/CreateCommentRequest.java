package com.assignment.gateway.dto;

import com.assignment.gateway.entity.ActorType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateCommentRequest {

    @NotNull
    private ActorType authorType;

    @NotNull
    private Long authorId;

    private Long parentCommentId;

    @NotBlank
    private String content;

    public ActorType getAuthorType() {
        return authorType;
    }

    public void setAuthorType(ActorType authorType) {
        this.authorType = authorType;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public Long getParentCommentId() {
        return parentCommentId;
    }

    public void setParentCommentId(Long parentCommentId) {
        this.parentCommentId = parentCommentId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}

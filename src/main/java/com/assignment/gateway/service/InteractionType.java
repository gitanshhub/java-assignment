package com.assignment.gateway.service;

public enum InteractionType {
    BOT_REPLY(1),
    HUMAN_LIKE(20),
    HUMAN_COMMENT(50);

    private final int points;

    InteractionType(int points) {
        this.points = points;
    }

    public int getPoints() {
        return points;
    }
}

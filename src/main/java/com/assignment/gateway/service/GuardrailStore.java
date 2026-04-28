package com.assignment.gateway.service;

import java.util.List;
import java.util.Set;

public interface GuardrailStore {

    long incrementViralityScore(Long postId, InteractionType interactionType);

    long getViralityScore(Long postId);

    long reserveBotReplySlot(Long postId);

    void releaseBotReplySlot(Long postId);

    boolean activateBotHumanCooldown(Long botId, Long humanId);

    boolean hasRecentNotificationCooldown(Long userId);

    void markNotificationCooldown(Long userId);

    void queuePendingNotification(Long userId, String message);

    Set<String> getPendingNotificationUsers();

    List<String> drainPendingNotifications(Long userId);
}

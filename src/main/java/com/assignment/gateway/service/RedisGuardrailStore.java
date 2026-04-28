package com.assignment.gateway.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Component
public class RedisGuardrailStore implements GuardrailStore {

    private static final Duration BOT_HUMAN_COOLDOWN = Duration.ofMinutes(10);
    private static final Duration USER_NOTIFICATION_COOLDOWN = Duration.ofMinutes(15);
    private static final String PENDING_USERS_KEY = "pending_notif_users";

    private final StringRedisTemplate redisTemplate;

    public RedisGuardrailStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public long incrementViralityScore(Long postId, InteractionType interactionType) {
        Long result = redisTemplate.opsForValue()
                .increment(viralityKey(postId), interactionType.getPoints());
        return result == null ? 0L : result;
    }

    @Override
    public long getViralityScore(Long postId) {
        String current = redisTemplate.opsForValue().get(viralityKey(postId));
        return current == null ? 0L : Long.parseLong(current);
    }

    @Override
    public long reserveBotReplySlot(Long postId) {
        Long result = redisTemplate.opsForValue().increment(botCountKey(postId));
        return result == null ? 0L : result;
    }

    @Override
    public void releaseBotReplySlot(Long postId) {
        redisTemplate.opsForValue().decrement(botCountKey(postId));
    }

    @Override
    public boolean activateBotHumanCooldown(Long botId, Long humanId) {
        Boolean created = redisTemplate.opsForValue()
                .setIfAbsent(cooldownKey(botId, humanId), "1", BOT_HUMAN_COOLDOWN);
        return Boolean.TRUE.equals(created);
    }

    @Override
    public boolean hasRecentNotificationCooldown(Long userId) {
        Boolean exists = redisTemplate.hasKey(userNotificationCooldownKey(userId));
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void markNotificationCooldown(Long userId) {
        redisTemplate.opsForValue()
                .set(userNotificationCooldownKey(userId), "1", USER_NOTIFICATION_COOLDOWN);
    }

    @Override
    public void queuePendingNotification(Long userId, String message) {
        redisTemplate.opsForList().rightPush(userPendingNotificationsKey(userId), message);
        redisTemplate.opsForSet().add(PENDING_USERS_KEY, String.valueOf(userId));
    }

    @Override
    public Set<String> getPendingNotificationUsers() {
        Set<String> members = redisTemplate.opsForSet().members(PENDING_USERS_KEY);
        return members == null ? Collections.emptySet() : members;
    }

    @Override
    public List<String> drainPendingNotifications(Long userId) {
        String listKey = userPendingNotificationsKey(userId);
        Long size = redisTemplate.opsForList().size(listKey);
        if (size == null || size == 0) {
            redisTemplate.opsForSet().remove(PENDING_USERS_KEY, String.valueOf(userId));
            return Collections.emptyList();
        }

        List<String> values = redisTemplate.opsForList().range(listKey, 0, -1);
        redisTemplate.delete(listKey);
        redisTemplate.opsForSet().remove(PENDING_USERS_KEY, String.valueOf(userId));
        return values == null ? Collections.emptyList() : values;
    }

    private String viralityKey(Long postId) {
        return "post:%d:virality_score".formatted(postId);
    }

    private String botCountKey(Long postId) {
        return "post:%d:bot_count".formatted(postId);
    }

    private String cooldownKey(Long botId, Long humanId) {
        return "cooldown:bot_%d:human_%d".formatted(botId, humanId);
    }

    private String userNotificationCooldownKey(Long userId) {
        return "user:%d:notif_cooldown".formatted(userId);
    }

    private String userPendingNotificationsKey(Long userId) {
        return "user:%d:pending_notifs".formatted(userId);
    }
}

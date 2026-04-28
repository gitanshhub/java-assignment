package com.assignment.gateway.scheduler;

import com.assignment.gateway.service.GuardrailStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class NotificationSweeper {

    private static final Logger log = LoggerFactory.getLogger(NotificationSweeper.class);
    private final GuardrailStore guardrailStore;

    public NotificationSweeper(GuardrailStore guardrailStore) {
        this.guardrailStore = guardrailStore;
    }

    @Scheduled(fixedRate = 300000)
    public void sweepPendingNotifications() {
        Set<String> pendingUsers = guardrailStore.getPendingNotificationUsers();
        for (String userIdValue : pendingUsers) {
            Long userId = Long.parseLong(userIdValue);
            List<String> notifications = guardrailStore.drainPendingNotifications(userId);
            if (notifications.isEmpty()) {
                continue;
            }

            String firstMessage = notifications.get(0);
            String botDescriptor = firstMessage.replace(" replied to your post", "");
            int remainingCount = notifications.size() - 1;

            if (remainingCount > 0) {
                log.info("Summarized Push Notification: {} and {} others interacted with your posts.", botDescriptor, remainingCount);
            } else {
                log.info("Summarized Push Notification: {} interacted with your posts.", botDescriptor);
            }
        }
    }
}

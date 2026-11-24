package com.urbancompany.platform.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Thin wrapper over the STOMP broker (topics + per-user queues). */
@Component
@RequiredArgsConstructor
public class RealtimePublisher {
    private final SimpMessagingTemplate template;

    public void toTopic(String destination, Object payload) { template.convertAndSend(destination, payload); }
    public void toUser(Long userId, String destination, Object payload) { template.convertAndSendToUser(String.valueOf(userId), destination, payload); }

    /** Runs the action once the surrounding transaction commits (so clients never see state that later rolls back). */
    public static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else r.run();
    }
}

package com.urbancompany.platform.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** In-app notification (persisted + WebSocket push) with SMS/Email provider stubs (log only). */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {
    private final JdbcClient jdbc;
    private final RealtimePublisher realtime;

    public void notify(Long userId, String title, String body) {
        if (userId == null) return;
        Long id = jdbc.sql("INSERT INTO notifications(user_id, title, body) VALUES (?,?,?) RETURNING id").param(userId).param(title).param(body).query(Long.class).single();
        realtime.toUser(userId, "/queue/notifications", Map.of("id", id, "title", title, "body", body));
        log.info("[SMS/EMAIL-STUB] to user {} :: {} - {}", userId, title, body);
    }

    public List<Map<String, Object>> list(Long userId, int page, int size) {
        return jdbc.sql("SELECT id, title, body, channel, is_read, created_at FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT ? OFFSET ?")
                .param(userId).param(size).param((long) page * size).query().listOfRows();
    }

    public int markAllRead(Long userId) { return jdbc.sql("UPDATE notifications SET is_read = TRUE WHERE user_id = ? AND is_read = FALSE").param(userId).update(); }
}

package com.urbancompany.platform.notification;

import com.urbancompany.platform.auth.Role;
import com.urbancompany.platform.booking.BookingAccessService;
import com.urbancompany.platform.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Customer <-> partner in-app chat scoped to a booking; persisted, then fanned out on /topic/bookings/{id}/chat. */
@Service
@RequiredArgsConstructor
public class ChatService {
    private final JdbcClient jdbc;
    private final BookingAccessService access;
    private final RealtimePublisher realtime;

    public Map<String, Object> send(Long userId, Role role, Long bookingId, String content) {
        if (!access.canAccess(userId, role, bookingId)) throw BusinessException.forbidden("Not part of this booking");
        if (content == null || content.isBlank() || content.length() > 1000) throw BusinessException.bad("INVALID_MESSAGE", "Message must be 1-1000 characters");
        Long id = jdbc.sql("INSERT INTO chat_messages(booking_id, sender_id, content) VALUES (?,?,?) RETURNING id").param(bookingId).param(userId).param(content.trim()).query(Long.class).single();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("bookingId", bookingId); m.put("senderId", userId); m.put("content", content.trim()); m.put("sentAt", Instant.now().toString());
        realtime.toTopic("/topic/bookings/" + bookingId + "/chat", m);
        return m;
    }

    public List<Map<String, Object>> history(Long userId, Role role, Long bookingId) {
        if (!access.canAccess(userId, role, bookingId)) throw BusinessException.forbidden("Not part of this booking");
        return jdbc.sql("SELECT id, sender_id, content, created_at FROM chat_messages WHERE booking_id = ? ORDER BY id").param(bookingId).query().listOfRows();
    }
}

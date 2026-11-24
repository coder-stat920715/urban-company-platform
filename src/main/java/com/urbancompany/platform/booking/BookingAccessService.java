package com.urbancompany.platform.booking;

import com.urbancompany.platform.auth.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BookingAccessService {
    private final JdbcClient jdbc;

    public boolean canAccess(Long userId, Role role, Long bookingId) {
        if (role == Role.ADMIN) return true;
        Integer n = jdbc.sql("SELECT count(*) FROM bookings b LEFT JOIN partners p ON p.id = b.partner_id WHERE b.id = ? AND (b.customer_id = ? OR p.user_id = ?)")
                .param(bookingId).param(userId).param(userId).query(Integer.class).single();
        return n > 0;
    }
}

package com.urbancompany.platform.partner;

import com.urbancompany.platform.common.Db;
import com.urbancompany.platform.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.*;

/** Working-hours, leave and booking-conflict checks for a single partner (used by dispatch). */
@Service
@RequiredArgsConstructor
public class PartnerAvailabilityService {
    public static final String ACTIVE_JOB_STATUSES =
            "('PARTNER_ASSIGNED','PARTNER_EN_ROUTE','ARRIVED_OTP_VERIFIED','IN_PROGRESS','EXTRA_WORK_PENDING_APPROVAL')";
    private final JdbcClient jdbc;
    private final AppProperties props;

    public boolean isWorking(Long partnerId, Instant start, Instant end) {
        ZoneId tz = ZoneId.of(props.timezone());
        ZonedDateTime s = start.atZone(tz), e = end.atZone(tz);
        Integer configured = jdbc.sql("SELECT count(*) FROM partner_working_hours WHERE partner_id = ?").param(partnerId).query(Integer.class).single();
        if (configured == 0) return !s.toLocalTime().isBefore(props.slots().dayStart()) && !e.toLocalTime().isAfter(props.slots().dayEnd()) && s.toLocalDate().equals(e.toLocalDate());
        Integer ok = jdbc.sql("SELECT count(*) FROM partner_working_hours WHERE partner_id = ? AND day_of_week = ? AND start_time <= ? AND end_time >= ?")
                .param(partnerId).param(s.getDayOfWeek().getValue()).param(java.sql.Time.valueOf(s.toLocalTime())).param(java.sql.Time.valueOf(e.toLocalTime()))
                .query(Integer.class).single();
        return ok > 0 && s.toLocalDate().equals(e.toLocalDate());
    }

    public boolean onLeave(Long partnerId, Instant start, Instant end) {
        Integer n = jdbc.sql("SELECT count(*) FROM partner_leaves WHERE partner_id = ? AND start_at < ? AND end_at > ?")
                .param(partnerId).param(Db.ts(end)).param(Db.ts(start)).query(Integer.class).single();
        return n > 0;
    }

    public boolean hasConflict(Long partnerId, Instant start, Instant end, Long excludeBookingId) {
        Duration buf = Duration.ofMinutes(props.slots().bufferMinutes());
        Integer n = jdbc.sql("SELECT count(*) FROM bookings WHERE partner_id = ? AND status IN " + ACTIVE_JOB_STATUSES
                        + " AND id <> ? AND scheduled_start < ? AND scheduled_end > ?")
                .param(partnerId).param(excludeBookingId == null ? -1L : excludeBookingId).param(Db.ts(end.plus(buf))).param(Db.ts(start.minus(buf)))
                .query(Integer.class).single();
        return n > 0;
    }

    public boolean isAvailable(Long partnerId, Instant start, Instant end, Long excludeBookingId) {
        return isWorking(partnerId, start, end) && !onLeave(partnerId, start, end) && !hasConflict(partnerId, start, end, excludeBookingId);
    }
}

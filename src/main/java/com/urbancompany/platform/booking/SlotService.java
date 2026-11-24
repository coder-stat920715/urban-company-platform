package com.urbancompany.platform.booking;

import com.urbancompany.platform.common.Db;
import com.urbancompany.platform.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Time;
import java.time.*;
import java.util.*;

/**
 * Scheduled slot availability: capacity = eligible partners (approved, in zone, skilled, working, not on leave, not busy incl. buffer)
 * minus not-yet-assigned bookings that overlap the slot. Booking creation re-checks capacity under a Redisson lock.
 */
@Service
@RequiredArgsConstructor
public class SlotService {
    private final JdbcClient jdbc;
    private final AppProperties props;

    public record Slot(Instant start, Instant end, boolean available) {}
    private record Window(LocalTime start, LocalTime end) {}
    private record Range(Long partnerId, Instant start, Instant end) {}
    private record DayData(List<Long> partners, Map<Long, Map<Integer, List<Window>>> hours, List<Range> leaves, List<Range> busy, List<Range> demand) {}

    private ZoneId tz() { return ZoneId.of(props.timezone()); }

    public List<Slot> slots(Long zoneId, Long categoryId, LocalDate date, int durationMinutes) {
        ZoneId tz = tz();
        Instant now = Instant.now();
        Instant earliest = now.plus(Duration.ofMinutes(props.slots().minLeadMinutes()));
        LocalDate today = LocalDate.now(tz);
        List<Slot> out = new ArrayList<>();
        if (date.isBefore(today) || date.isAfter(today.plusDays(props.slots().horizonDays()))) return out;
        DayData data = load(zoneId, categoryId, date);
        LocalTime t = props.slots().dayStart();
        while (!t.plusMinutes(durationMinutes).isAfter(props.slots().dayEnd()) && !t.plusMinutes(durationMinutes).isBefore(t)) {
            Instant s = date.atTime(t).atZone(tz).toInstant();
            Instant e = s.plus(Duration.ofMinutes(durationMinutes));
            out.add(new Slot(s, e, !s.isBefore(earliest) && capacity(data, s, e, date) > 0));
            t = t.plusMinutes(props.slots().stepMinutes());
        }
        return out;
    }

    public int capacityLeft(Long zoneId, Long categoryId, Instant start, int durationMinutes) {
        LocalDate date = start.atZone(tz()).toLocalDate();
        return capacity(load(zoneId, categoryId, date), start, start.plus(Duration.ofMinutes(durationMinutes)), date);
    }

    private int capacity(DayData d, Instant s, Instant e, LocalDate date) {
        Duration buf = Duration.ofMinutes(props.slots().bufferMinutes());
        int free = 0;
        for (Long p : d.partners()) {
            if (!working(d, p, s, e)) continue;
            boolean leave = d.leaves().stream().anyMatch(r -> r.partnerId().equals(p) && r.start().isBefore(e) && r.end().isAfter(s));
            boolean busy = d.busy().stream().anyMatch(r -> r.partnerId().equals(p) && r.start().minus(buf).isBefore(e) && r.end().plus(buf).isAfter(s));
            if (!leave && !busy) free++;
        }
        long demand = d.demand().stream().filter(r -> r.start().minus(buf).isBefore(e) && r.end().plus(buf).isAfter(s)).count();
        return (int) (free - demand);
    }

    private boolean working(DayData d, Long partnerId, Instant s, Instant e) {
        ZonedDateTime zs = s.atZone(tz()), ze = e.atZone(tz());
        if (!zs.toLocalDate().equals(ze.toLocalDate())) return false;
        Map<Integer, List<Window>> byDay = d.hours().get(partnerId);
        if (byDay == null) return !zs.toLocalTime().isBefore(props.slots().dayStart()) && !ze.toLocalTime().isAfter(props.slots().dayEnd());
        return byDay.getOrDefault(zs.getDayOfWeek().getValue(), List.of()).stream()
                .anyMatch(w -> !zs.toLocalTime().isBefore(w.start()) && !ze.toLocalTime().isAfter(w.end()));
    }

    private DayData load(Long zoneId, Long categoryId, LocalDate date) {
        ZoneId tz = tz();
        Instant dayStart = date.atStartOfDay(tz).toInstant().minus(Duration.ofHours(6));
        Instant dayEnd = date.plusDays(1).atStartOfDay(tz).toInstant().plus(Duration.ofHours(6));
        List<Long> partners = jdbc.sql("SELECT p.id FROM partners p JOIN partner_skills s ON s.partner_id = p.id WHERE p.status = 'APPROVED' AND p.zone_id = ? AND s.category_id = ?")
                .param(zoneId).param(categoryId).query(Long.class).list();
        Map<Long, Map<Integer, List<Window>>> hours = new HashMap<>();
        List<Range> leaves = new ArrayList<>(), busy = new ArrayList<>();
        if (!partners.isEmpty()) {
            for (Map<String, Object> r : jdbc.sql("SELECT partner_id, day_of_week, start_time, end_time FROM partner_working_hours WHERE partner_id IN (:ids)").param("ids", partners).query().listOfRows())
                hours.computeIfAbsent(((Number) r.get("partner_id")).longValue(), k -> new HashMap<>())
                        .computeIfAbsent(((Number) r.get("day_of_week")).intValue(), k -> new ArrayList<>())
                        .add(new Window(((Time) r.get("start_time")).toLocalTime(), ((Time) r.get("end_time")).toLocalTime()));
            for (Map<String, Object> r : jdbc.sql("SELECT partner_id, start_at, end_at FROM partner_leaves WHERE partner_id IN (:ids) AND start_at < :e AND end_at > :s")
                    .param("ids", partners).param("s", Db.ts(dayStart)).param("e", Db.ts(dayEnd)).query().listOfRows())
                leaves.add(new Range(((Number) r.get("partner_id")).longValue(), Db.instant(r.get("start_at")), Db.instant(r.get("end_at"))));
            for (Map<String, Object> r : jdbc.sql("SELECT partner_id, scheduled_start, scheduled_end FROM bookings WHERE partner_id IN (:ids) AND status IN (" + activeStatuses()
                    + ") AND scheduled_start < :e AND scheduled_end > :s").param("ids", partners).param("s", Db.ts(dayStart)).param("e", Db.ts(dayEnd)).query().listOfRows())
                busy.add(new Range(((Number) r.get("partner_id")).longValue(), Db.instant(r.get("scheduled_start")), Db.instant(r.get("scheduled_end"))));
        }
        List<Range> demand = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT scheduled_start, scheduled_end FROM bookings WHERE zone_id = :z AND category_id = :c AND partner_id IS NULL "
                + "AND status IN ('PAYMENT_PENDING','CONFIRMED','SEARCHING_PARTNER') AND scheduled_start < :e AND scheduled_end > :s")
                .param("z", zoneId).param("c", categoryId).param("s", Db.ts(dayStart)).param("e", Db.ts(dayEnd)).query().listOfRows())
            demand.add(new Range(null, Db.instant(r.get("scheduled_start")), Db.instant(r.get("scheduled_end"))));
        return new DayData(partners, hours, leaves, busy, demand);
    }

    private String activeStatuses() { return "'PARTNER_ASSIGNED','PARTNER_EN_ROUTE','ARRIVED_OTP_VERIFIED','IN_PROGRESS','EXTRA_WORK_PENDING_APPROVAL'"; }
}

package com.urbancompany.platform.admin;

import com.urbancompany.platform.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Operations analytics & reports. */
@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {
    private final JdbcClient jdbc;

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookingsByStatus", jdbc.sql("SELECT status, count(*) AS count FROM bookings GROUP BY status ORDER BY count DESC").query().listOfRows());
        m.put("gmv", jdbc.sql("SELECT COALESCE(SUM(total + extra_amount),0) FROM bookings WHERE status IN ('COMPLETED','SETTLED')").query(java.math.BigDecimal.class).single());
        m.put("refundsTotal", jdbc.sql("SELECT COALESCE(SUM(amount),0) FROM refunds").query(java.math.BigDecimal.class).single());
        m.put("platformCommission", jdbc.sql("SELECT COALESCE(SUM(commission_amount),0) FROM partner_earnings").query(java.math.BigDecimal.class).single());
        m.put("partnersByStatus", jdbc.sql("SELECT status, count(*) AS count FROM partners GROUP BY status").query().listOfRows());
        m.put("onlinePartners", jdbc.sql("SELECT count(*) FROM partners WHERE online = TRUE").query(Long.class).single());
        m.put("avgPartnerRating", jdbc.sql("SELECT COALESCE(AVG(rating_avg) FILTER (WHERE rating_count > 0),0) FROM partners").query(Double.class).single());
        return ApiResponse.ok(m);
    }

    @GetMapping("/bookings-daily")
    public ApiResponse<List<Map<String, Object>>> daily(@RequestParam(defaultValue = "14") int days) {
        return ApiResponse.ok(jdbc.sql("""
                SELECT date_trunc('day', created_at)::date AS day, count(*) AS bookings,
                       count(*) FILTER (WHERE status IN ('COMPLETED','SETTLED')) AS completed,
                       count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
                       COALESCE(SUM(total) FILTER (WHERE status IN ('COMPLETED','SETTLED')),0) AS gmv
                FROM bookings WHERE created_at > now() - make_interval(days => ?) GROUP BY 1 ORDER BY 1 DESC""").param(Math.min(days, 90)).query().listOfRows());
    }

    @GetMapping("/top-partners")
    public ApiResponse<List<Map<String, Object>>> top() {
        return ApiResponse.ok(jdbc.sql("""
                SELECT p.id, p.display_name, p.rating_avg, p.jobs_completed, p.acceptance_rate, COALESCE(SUM(e.net_amount),0) AS net_earnings
                FROM partners p LEFT JOIN partner_earnings e ON e.partner_id = p.id GROUP BY p.id ORDER BY p.jobs_completed DESC, p.rating_avg DESC LIMIT 20""").query().listOfRows());
    }

    @GetMapping("/audit")
    public ApiResponse<List<Map<String, Object>>> audit(@RequestParam(required = false) String entity, @RequestParam(required = false) String entityId, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(jdbc.sql("SELECT id, actor, action, entity, entity_id, created_at FROM audit_logs WHERE (CAST(:e AS text) IS NULL OR entity = :e) AND (CAST(:i AS text) IS NULL OR entity_id = :i) ORDER BY id DESC LIMIT :n")
                .param("e", entity).param("i", entityId).param("n", Math.min(size, 200)).query().listOfRows());
    }
}

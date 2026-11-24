package com.urbancompany.platform.partner;

import com.urbancompany.platform.common.*;
import com.urbancompany.platform.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Earnings ledger: commission split (tiered), TDS, itemized history and withdrawals. */
@Service
@RequiredArgsConstructor
public class EarningsService {
    private final JdbcClient jdbc;
    private final PartnerRepository partners;
    private final CommissionTierRepository tiers;
    private final AppProperties props;
    private final DomainEventPublisher events;
    private final RedissonClient redisson;
    private final TransactionTemplate tx;

    public BigDecimal commissionPercentFor(int jobsCompleted) {
        return tiers.findAllByOrderByMinJobsDesc().stream().filter(t -> jobsCompleted >= t.getMinJobs()).findFirst()
                .map(CommissionTier::getCommissionPercent).orElse(new BigDecimal("25.00"));
    }

    /** Idempotent: one ledger row per booking (UNIQUE booking_id). Must run inside a transaction. */
    public boolean record(Long bookingId, Long partnerId, BigDecimal gross) {
        Partner p = partners.findById(partnerId).orElseThrow();
        BigDecimal pct = commissionPercentFor(Math.max(0, p.getJobsCompleted() - 1));
        BigDecimal commission = Money.pct(gross, pct);
        BigDecimal tds = Money.pct(gross, props.earnings().tdsPercent());
        BigDecimal net = Money.r(gross.subtract(commission).subtract(tds));
        int rows = jdbc.sql("""
                INSERT INTO partner_earnings(partner_id, booking_id, gross_amount, commission_percent, commission_amount, tds_amount, net_amount, status)
                VALUES (?,?,?,?,?,?,?, 'AVAILABLE') ON CONFLICT (booking_id) DO NOTHING""")
                .param(partnerId).param(bookingId).param(Money.r(gross)).param(pct).param(commission).param(tds).param(net).update();
        if (rows == 0) return false;
        events.publish(Topics.EARNINGS, "EARNING", bookingId, "EARNING_RECORDED",
                Map.of("bookingId", bookingId, "partnerId", partnerId, "partnerUserId", p.getUserId(), "gross", gross, "commission", commission, "tds", tds, "net", net));
        return true;
    }

    public Map<String, Object> summary(Long partnerId) {
        Map<String, Object> row = jdbc.sql("""
                SELECT COALESCE(SUM(gross_amount),0) gross, COALESCE(SUM(commission_amount),0) commission,
                       COALESCE(SUM(tds_amount),0) tds, COALESCE(SUM(net_amount),0) net, COUNT(*) jobs
                FROM partner_earnings WHERE partner_id = ?""").param(partnerId).query().singleRow();
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("withdrawn", withdrawn(partnerId));
        out.put("availableBalance", available(partnerId));
        return out;
    }

    private BigDecimal withdrawn(Long partnerId) {
        return jdbc.sql("SELECT COALESCE(SUM(amount),0) FROM withdrawal_requests WHERE partner_id = ? AND status IN ('REQUESTED','PROCESSED')")
                .param(partnerId).query(BigDecimal.class).single();
    }

    public BigDecimal available(Long partnerId) {
        BigDecimal net = jdbc.sql("SELECT COALESCE(SUM(net_amount),0) FROM partner_earnings WHERE partner_id = ?").param(partnerId).query(BigDecimal.class).single();
        return Money.r(net.subtract(withdrawn(partnerId)));
    }

    public List<Map<String, Object>> history(Long partnerId, int page, int size) {
        return jdbc.sql("""
                SELECT e.id, e.booking_id, b.booking_code, e.gross_amount, e.commission_percent, e.commission_amount, e.tds_amount, e.net_amount, e.status, e.created_at
                FROM partner_earnings e JOIN bookings b ON b.id = e.booking_id
                WHERE e.partner_id = ? ORDER BY e.id DESC LIMIT ? OFFSET ?""").param(partnerId).param(size).param((long) page * size).query().listOfRows();
    }

    public List<Map<String, Object>> tdsSummary(Long partnerId) {
        return jdbc.sql("""
                SELECT to_char(created_at, 'YYYY-MM') AS month, SUM(gross_amount) AS gross, SUM(tds_amount) AS tds_deducted, COUNT(*) AS jobs
                FROM partner_earnings WHERE partner_id = ? GROUP BY 1 ORDER BY 1 DESC""").param(partnerId).query().listOfRows();
    }

    public Map<String, Object> requestWithdrawal(Long userId, BigDecimal amount) {
        Partner p = partners.findByUserId(userId).orElseThrow(() -> BusinessException.notFound("Partner profile"));
        if (p.getBankAccountNo() == null || p.getBankIfsc() == null) throw BusinessException.unprocessable("BANK_DETAILS_REQUIRED", "Add bank details first");
        if (amount.compareTo(props.earnings().minWithdrawal()) < 0) throw BusinessException.bad("BELOW_MINIMUM", "Minimum withdrawal is " + props.earnings().minWithdrawal());
        RLock lock = redisson.getLock("lock:withdraw:" + p.getId());
        try {
            if (!lock.tryLock(3, 10, TimeUnit.SECONDS)) throw BusinessException.conflict("BUSY", "Another withdrawal is in progress");
            try {
                return tx.execute(s -> {
                    if (available(p.getId()).compareTo(amount) < 0) throw BusinessException.unprocessable("INSUFFICIENT_BALANCE", "Insufficient earnings balance");
                    Long id = jdbc.sql("INSERT INTO withdrawal_requests(partner_id, amount, bank_account_no, bank_ifsc) VALUES (?,?,?,?) RETURNING id")
                            .param(p.getId()).param(Money.r(amount)).param(p.getBankAccountNo()).param(p.getBankIfsc()).query(Long.class).single();
                    events.publish(Topics.EARNINGS, "WITHDRAWAL", id, "WITHDRAWAL_REQUESTED", Map.of("withdrawalId", id, "partnerId", p.getId(), "partnerUserId", p.getUserId(), "amount", amount));
                    return Map.<String, Object>of("withdrawalId", id, "status", "REQUESTED", "amount", amount);
                });
            } finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw BusinessException.conflict("INTERRUPTED", "Interrupted"); }
    }

    public List<Map<String, Object>> withdrawals(Long partnerId) {
        return jdbc.sql("SELECT id, amount, status, remarks, created_at, processed_at FROM withdrawal_requests WHERE partner_id = ? ORDER BY id DESC")
                .param(partnerId).query().listOfRows();
    }

    public List<Map<String, Object>> pendingWithdrawals() {
        return jdbc.sql("SELECT * FROM withdrawal_requests WHERE status = 'REQUESTED' ORDER BY id").query().listOfRows();
    }

    public void processWithdrawal(Long id, boolean approve, String remarks) {
        int n = jdbc.sql("UPDATE withdrawal_requests SET status = ?, remarks = ?, processed_at = now() WHERE id = ? AND status = 'REQUESTED'")
                .param(approve ? "PROCESSED" : "REJECTED").param(remarks).param(id).update();
        if (n == 0) throw BusinessException.conflict("INVALID_STATE", "Withdrawal not pending");
    }
}

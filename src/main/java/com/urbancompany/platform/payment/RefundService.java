package com.urbancompany.platform.payment;

import com.urbancompany.platform.booking.BookingCancelledEvent;
import com.urbancompany.platform.booking.BookingRefundEvent;
import com.urbancompany.platform.common.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Refund orchestration. The wallet-funded share is returned to the UC wallet; the gateway share is recorded as a processed refund to source. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {
    private final PaymentRepository payments;
    private final WalletService wallets;
    private final JdbcClient jdbc;
    private final DomainEventPublisher events;

    /** Synchronous listener: runs inside the cancelling transaction, so cancel + refund are atomic. */
    @EventListener
    public void onBookingCancelled(BookingCancelledEvent e) {
        if (e.refundAmount().signum() > 0) refund(e.bookingId(), e.customerId(), e.refundAmount(), e.reason());
    }

    @EventListener
    public void onRefundRequested(BookingRefundEvent e) { refund(e.bookingId(), e.customerId(), e.amount(), e.reason()); }

    @Transactional
    public BigDecimal refund(Long bookingId, Long userId, BigDecimal amount, String reason) {
        List<Payment> paid = payments.findByBookingIdAndStatus(bookingId, "PAID");
        BigDecimal paidTotal = paid.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal alreadyRefunded = jdbc.sql("SELECT COALESCE(SUM(amount),0) FROM refunds WHERE booking_id = ?").param(bookingId).query(BigDecimal.class).single();
        BigDecimal refundable = Money.max(Money.ZERO, paidTotal.subtract(alreadyRefunded));
        BigDecimal refundAmt = Money.min(Money.r(amount), refundable);
        if (refundAmt.signum() <= 0) return Money.ZERO;
        BigDecimal walletPaid = paid.stream().map(Payment::getWalletAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal toWallet = Money.min(refundAmt, walletPaid);
        BigDecimal toGateway = Money.r(refundAmt.subtract(toWallet));
        if (toWallet.signum() > 0) wallets.credit(userId, toWallet, "REFUND", "booking:" + bookingId, reason);
        Long id = jdbc.sql("INSERT INTO refunds(booking_id, amount, wallet_amount, gateway_amount, status, reason) VALUES (?,?,?,?, 'PROCESSED', ?) RETURNING id")
                .param(bookingId).param(refundAmt).param(toWallet).param(toGateway).param(reason).query(Long.class).single();
        events.publish(Topics.PAYMENT, "REFUND", id, "REFUND_PROCESSED",
                Map.of("userId", userId, "bookingId", bookingId, "amount", refundAmt, "toWallet", toWallet, "toGateway", toGateway));
        return refundAmt;
    }
}

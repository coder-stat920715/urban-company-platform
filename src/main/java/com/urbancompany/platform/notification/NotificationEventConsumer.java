package com.urbancompany.platform.notification;

import com.urbancompany.platform.common.JsonUtil;
import com.urbancompany.platform.common.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** Kafka consumer: turns domain events (from the transactional outbox) into WebSocket pushes and notifications. */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventConsumer {
    private final NotificationService notifications;
    private final RealtimePublisher realtime;
    private final JdbcClient jdbc;

    @KafkaListener(topics = {Topics.BOOKING, Topics.DISPATCH, Topics.PAYMENT, Topics.EARNINGS, Topics.PARTNER}, groupId = "uc-notifications")
    public void onEvent(String payload) {
        try { handle(JsonUtil.toMap(payload)); }
        catch (Exception e) { log.warn("Notification handling failed: {}", e.toString()); }
    }

    private void handle(Map<String, Object> e) {
        String type = String.valueOf(e.get("eventType"));
        Long bookingId = num(e, "bookingId"), customerId = num(e, "customerId");
        Long partnerUser = partnerUser(num(e, "partnerId"));
        String code = String.valueOf(e.getOrDefault("bookingCode", ""));
        switch (type) {
            case "BOOKING_STATUS_CHANGED" -> {
                String to = String.valueOf(e.get("to"));
                realtime.toTopic("/topic/bookings/" + bookingId + "/status", Map.of("bookingId", bookingId, "status", to, "from", String.valueOf(e.get("from")), "at", String.valueOf(e.get("occurredAt"))));
                switch (to) {
                    case "CONFIRMED" -> notifications.notify(customerId, "Booking confirmed", "Booking " + code + " is confirmed. We're finding you a professional.");
                    case "PARTNER_EN_ROUTE" -> notifications.notify(customerId, "Professional on the way", "Track live location in the app. Keep your start OTP ready.");
                    case "ARRIVED_OTP_VERIFIED" -> notifications.notify(customerId, "Job verified", "Start OTP verified. Your service is about to begin.");
                    case "COMPLETED" -> notifications.notify(customerId, "Service completed", "Share your end OTP only after you are satisfied. Please rate your experience.");
                    default -> { }
                }
            }
            case "BOOKING_CANCELLED" -> {
                notifications.notify(customerId, "Booking cancelled", "Booking " + code + " was cancelled. Refund: " + e.get("refund"));
                notifications.notify(partnerUser, "Job cancelled", "Booking " + code + " was cancelled.");
            }
            case "PARTNER_ASSIGNED" -> notifications.notify(customerId, "Professional assigned", String.valueOf(e.getOrDefault("partnerName", "A professional")) + " will serve booking " + code + ".");
            case "NO_PARTNER_FOUND" -> notifications.notify(customerId, "No professional available", "Sorry, we could not find a professional. You have been fully refunded.");
            case "PARTNER_ARRIVED" -> notifications.notify(customerId, "Professional has arrived", "Share your 4-digit start OTP with the professional to begin.");
            case "EXTRA_WORK_REQUESTED" -> {
                realtime.toTopic("/topic/bookings/" + bookingId + "/extra-work", Map.of("bookingId", bookingId, "extraWorkId", e.get("extraWorkId"), "description", String.valueOf(e.get("description")), "amount", e.get("amount")));
                notifications.notify(customerId, "Approval needed", "Additional work quoted: Rs " + e.get("amount") + ". Approve or reject in the app.");
            }
            case "EXTRA_WORK_RESOLVED" -> {
                realtime.toTopic("/topic/bookings/" + bookingId + "/extra-work", Map.of("bookingId", bookingId, "extraWorkId", e.get("extraWorkId"), "approved", e.get("approved")));
                notifications.notify(partnerUser, "Extra work " + (Boolean.TRUE.equals(e.get("approved")) ? "approved" : "rejected"), "Customer decision for booking " + code);
            }
            case "BOOKING_COMPLETED" -> notifications.notify(partnerUser, "Job completed", "Great work on booking " + code + ". Earnings will be credited shortly.");
            case "OFFER_CREATED" -> notifications.notify(num(e, "partnerUserId"), "New job offer", "Accept within 30 seconds. Booking " + code);
            case "PAYMENT_SUCCEEDED" -> notifications.notify(num(e, "userId"), "Payment successful", "Received Rs " + e.get("amount") + " via " + e.get("method"));
            case "PAYMENT_FAILED" -> notifications.notify(num(e, "userId"), "Payment failed", "Your payment could not be completed. Please retry.");
            case "REFUND_PROCESSED" -> notifications.notify(num(e, "userId"), "Refund processed", "Rs " + e.get("amount") + " refunded (wallet: " + e.get("toWallet") + ").");
            case "MEMBERSHIP_ACTIVATED" -> notifications.notify(num(e, "userId"), "Welcome to UC Plus", "Enjoy discounts and zero convenience fee.");
            case "EARNING_RECORDED" -> notifications.notify(num(e, "partnerUserId"), "Earnings credited", "Net earning Rs " + e.get("net") + " added to your ledger.");
            case "WITHDRAWAL_REQUESTED" -> notifications.notify(num(e, "partnerUserId"), "Withdrawal requested", "Rs " + e.get("amount") + " withdrawal is being processed.");
            case "KYC_STATUS_CHANGED" -> notifications.notify(num(e, "userId"), "Account status: " + e.get("status"), "Your partner account status is now " + e.get("status") + ".");
            case "WARRANTY_RESOLVED" -> notifications.notify(customerId, "Warranty claim " + e.get("decision"), String.valueOf(e.getOrDefault("note", "")));
            default -> { }
        }
    }

    private Long partnerUser(Long partnerId) {
        if (partnerId == null) return null;
        Optional<Long> u = jdbc.sql("SELECT user_id FROM partners WHERE id = ?").param(partnerId).query(Long.class).optional();
        return u.orElse(null);
    }

    private static Long num(Map<String, Object> e, String k) {
        Object v = e.get(k);
        return v instanceof Number n ? n.longValue() : null;
    }
}

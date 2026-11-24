package com.urbancompany.platform.payment;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Entity @Table(name = "payments")
@Getter @Setter
public class Payment extends BaseEntity {
    private String purpose;                // BOOKING | MEMBERSHIP
    @Column(name = "booking_id") private Long bookingId;
    @Column(name = "plan_id") private Long planId;
    @Column(name = "user_id") private Long userId;
    private BigDecimal amount;             // total payable
    @Column(name = "wallet_amount") private BigDecimal walletAmount = BigDecimal.ZERO;
    private String method;                 // UPI | CARD | NETBANKING | WALLET
    private String status;                 // CREATED | PAID | FAILED
    @Column(name = "intent_id") private String intentId;
    @Column(name = "gateway_ref") private String gatewayRef;
    @Column(name = "failure_reason") private String failureReason;
}

package com.urbancompany.platform.payment;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "memberships")
@Getter @Setter
public class Membership extends BaseEntity {
    @Column(name = "user_id") private Long userId;
    @Column(name = "plan_id") private Long planId;
    @Column(name = "start_at") private Instant startAt;
    @Column(name = "end_at") private Instant endAt;
    private String status = "ACTIVE";
}

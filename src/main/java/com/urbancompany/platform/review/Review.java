package com.urbancompany.platform.review;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "reviews")
@Getter @Setter
public class Review extends BaseEntity {
    private Long bookingId;
    private Long customerId;
    private Long partnerId;
    private int rating;
    private String comment;
}

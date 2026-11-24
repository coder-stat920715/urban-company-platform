package com.urbancompany.platform.booking;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Entity @Table(name = "booking_items")
@Getter @Setter
public class BookingItem extends BaseEntity {
    private Long bookingId;
    private Long packageId;
    private String packageName;
    private int quantity;
    private String optionKeys;
    private BigDecimal unitPrice;
    private BigDecimal lineTotal;
    private int durationMinutes;
}

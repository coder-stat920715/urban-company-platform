package com.urbancompany.platform.payment;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Entity @Table(name = "wallets")
@Getter @Setter
public class Wallet extends BaseEntity {
    @Column(name = "user_id") private Long userId;
    private BigDecimal balance = BigDecimal.ZERO;
    /** Optimistic locking: concurrent debits/credits on the same wallet cannot silently overwrite each other. */
    @Version private Long version;
}

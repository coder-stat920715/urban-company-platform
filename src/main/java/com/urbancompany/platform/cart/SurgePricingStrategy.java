package com.urbancompany.platform.cart;

import com.urbancompany.platform.common.Money;
import com.urbancompany.platform.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Peak-hour surge: highest matching rule multiplier applies to the subtotal. */
@Component @Order(10)
@RequiredArgsConstructor
public class SurgePricingStrategy implements PricingStrategy {
    private final SurgeRuleRepository rules;
    private final AppProperties props;

    @Override
    public void apply(PricingContext ctx, PriceBreakdown b) {
        ZonedDateTime t = ctx.scheduledStart().atZone(ZoneId.of(props.timezone()));
        BigDecimal best = BigDecimal.ONE;
        String bestName = null;
        for (SurgeRule r : rules.findByActiveTrue()) {
            boolean zoneOk = r.getZoneId() == null || r.getZoneId().equals(ctx.zoneId());
            boolean hourOk = t.getHour() >= r.getStartHour() && t.getHour() < r.getEndHour();
            boolean dayOk = r.getDayOfWeek() == null || r.getDayOfWeek() == t.getDayOfWeek().getValue();
            if (zoneOk && hourOk && dayOk && r.getMultiplier().compareTo(best) > 0) { best = r.getMultiplier(); bestName = r.getName(); }
        }
        if (best.compareTo(BigDecimal.ONE) > 0) {
            b.setSurgeAmount(Money.r(b.getSurgeAmount().add(b.getSubtotal().multiply(best.subtract(BigDecimal.ONE)))));
            b.getNotes().add("Surge x" + best + " (" + bestName + ")");
        }
    }
}

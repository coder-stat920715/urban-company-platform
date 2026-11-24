package com.urbancompany.platform.cart;

import com.urbancompany.platform.cart.CartModels.CartLine;
import com.urbancompany.platform.common.BusinessException;
import com.urbancompany.platform.common.Money;
import com.urbancompany.platform.location.Address;
import com.urbancompany.platform.location.AddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CartQuoteService {
    private final CartService cart;
    private final AddressService addresses;
    private final PricingEngine engine;

    public record QuoteRequest(Long addressId, Long categoryId, Instant scheduledStart, boolean instant, String couponCode) {}

    public Map<String, Object> quote(Long userId, QuoteRequest r) {
        List<CartLine> lines = cart.linesForCheckout(userId, r.categoryId());
        Address a = addresses.owned(userId, r.addressId());
        if (r.instant() && lines.stream().anyMatch(l -> !l.instantEligible())) throw BusinessException.unprocessable("NOT_INSTANT_ELIGIBLE", "Some items are not available for Insta-Service");
        Instant start = r.instant() || r.scheduledStart() == null ? Instant.now() : r.scheduledStart();
        BigDecimal subtotal = lines.stream().map(CartLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        PriceBreakdown b = engine.price(new PricingContext(userId, a.getZoneId(), start, r.instant(), Money.r(subtotal), r.couponCode()));
        return Map.of("lines", lines, "durationMinutes", lines.stream().mapToInt(CartLine::durationMinutes).sum(), "pricing", b);
    }
}

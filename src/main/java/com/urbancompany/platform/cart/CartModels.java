package com.urbancompany.platform.cart;

import java.math.BigDecimal;
import java.util.List;

public final class CartModels {
    private CartModels() {}
    public record CartLine(String lineId, Long packageId, String packageName, Long categoryId, String categoryName, int quantity,
                           List<String> optionKeys, BigDecimal unitPrice, BigDecimal lineTotal, int durationMinutes, boolean instantEligible) {}
    public record CartGroup(Long categoryId, String categoryName, List<CartLine> lines, BigDecimal subtotal, int durationMinutes) {}
    public record CartView(List<CartLine> lines, List<CartGroup> groups, BigDecimal subtotal, int totalDurationMinutes, boolean multiCategory) {}
}

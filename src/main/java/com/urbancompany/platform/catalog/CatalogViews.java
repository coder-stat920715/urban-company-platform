package com.urbancompany.platform.catalog;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

/** Read models returned by the catalog API. */
public final class CatalogViews {
    private CatalogViews() {}

    public record CategoryNode(Long id, String name, String slug, String iconUrl, List<CategoryNode> children) {}
    public record ServiceSummary(Long id, String name, String slug, String description, BigDecimal startingPrice) {}
    public record OptionView(Long id, String key, String label, BigDecimal priceDelta, int durationDeltaMinutes) {}
    public record QuestionView(Long id, String key, String text, QuestionType type, boolean required, List<OptionView> options) {}
    public record RateCardView(Long id, String itemName, BigDecimal price, String unit, String description) {}

    @Builder
    public record PackageDetail(Long id, Long serviceId, Long categoryId, String categoryName, String name, String description,
                                BigDecimal basePrice, int durationMinutes, int maxQuantity, boolean instantEligible,
                                String attributesJson, List<QuestionView> questions) {}

    public record ServiceDetail(Long id, String name, String description, Long categoryId, List<PackageDetail> packages, List<RateCardView> rateCard) {}
}

package com.urbancompany.platform.catalog;

import com.urbancompany.platform.common.ApiResponse;
import com.urbancompany.platform.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/** Admin CRUD for the full catalog hierarchy. Categories/Services/Packages are soft-deleted (active=false) to keep booking history intact. */
@RestController
@RequestMapping("/api/v1/admin/catalog")
@RequiredArgsConstructor
@Transactional
public class AdminCatalogController {
    private final CategoryRepository categories;
    private final ServiceOfferingRepository services;
    private final ServicePackageRepository packages;
    private final AddonQuestionRepository questions;
    private final AddonOptionRepository options;
    private final RateCardRepository rateCards;

    public record CategoryReq(Long parentId, @NotBlank String name, @NotBlank String slug, String iconUrl, int sortOrder, Boolean active) {}
    public record ServiceReq(@NotNull Long categoryId, @NotBlank String name, @NotBlank String slug, String description, Boolean active) {}
    public record PackageReq(@NotNull Long serviceId, @NotBlank String name, String description, @NotNull @Positive BigDecimal basePrice,
                             @Positive int durationMinutes, @Positive int maxQuantity, boolean instantEligible, String attributesJson, Boolean active) {}
    public record QuestionReq(@NotNull Long packageId, @NotBlank String questionKey, @NotBlank String questionText, @NotNull QuestionType type, boolean required, int sortOrder) {}
    public record OptionReq(@NotNull Long questionId, @NotBlank String optionKey, @NotBlank String label, @NotNull BigDecimal priceDelta, int durationDeltaMinutes, Boolean active) {}
    public record RateCardReq(@NotNull Long serviceId, @NotBlank String itemName, @NotNull @PositiveOrZero BigDecimal price, String unit, String description, Boolean active) {}

    private <T> T find(JpaRepository<T, Long> repo, Long id, String what) { return repo.findById(id).orElseThrow(() -> BusinessException.notFound(what)); }

    // ---- Categories / Subcategories ----
    @PostMapping("/categories") public ApiResponse<Category> createCategory(@Valid @RequestBody CategoryReq r) { return ApiResponse.ok(categories.save(apply(new Category(), r))); }
    @PutMapping("/categories/{id}") public ApiResponse<Category> updateCategory(@PathVariable Long id, @Valid @RequestBody CategoryReq r) { return ApiResponse.ok(categories.save(apply(find(categories, id, "Category"), r))); }
    @DeleteMapping("/categories/{id}") public ApiResponse<Void> deleteCategory(@PathVariable Long id) { Category c = find(categories, id, "Category"); c.setActive(false); return ApiResponse.ok(null); }
    private Category apply(Category c, CategoryReq r) {
        c.setParentId(r.parentId()); c.setName(r.name()); c.setSlug(r.slug()); c.setIconUrl(r.iconUrl()); c.setSortOrder(r.sortOrder());
        if (r.active() != null) c.setActive(r.active());
        return c;
    }

    // ---- Services ----
    @PostMapping("/services") public ApiResponse<ServiceOffering> createService(@Valid @RequestBody ServiceReq r) { return ApiResponse.ok(services.save(apply(new ServiceOffering(), r))); }
    @PutMapping("/services/{id}") public ApiResponse<ServiceOffering> updateService(@PathVariable Long id, @Valid @RequestBody ServiceReq r) { return ApiResponse.ok(services.save(apply(find(services, id, "Service"), r))); }
    @DeleteMapping("/services/{id}") public ApiResponse<Void> deleteService(@PathVariable Long id) { find(services, id, "Service").setActive(false); return ApiResponse.ok(null); }
    private ServiceOffering apply(ServiceOffering s, ServiceReq r) {
        s.setCategoryId(r.categoryId()); s.setName(r.name()); s.setSlug(r.slug()); s.setDescription(r.description());
        if (r.active() != null) s.setActive(r.active());
        return s;
    }

    // ---- Packages ----
    @PostMapping("/packages") public ApiResponse<ServicePackage> createPackage(@Valid @RequestBody PackageReq r) { return ApiResponse.ok(packages.save(apply(new ServicePackage(), r))); }
    @PutMapping("/packages/{id}") public ApiResponse<ServicePackage> updatePackage(@PathVariable Long id, @Valid @RequestBody PackageReq r) { return ApiResponse.ok(packages.save(apply(find(packages, id, "Package"), r))); }
    @DeleteMapping("/packages/{id}") public ApiResponse<Void> deletePackage(@PathVariable Long id) { find(packages, id, "Package").setActive(false); return ApiResponse.ok(null); }
    private ServicePackage apply(ServicePackage p, PackageReq r) {
        p.setServiceId(r.serviceId()); p.setName(r.name()); p.setDescription(r.description()); p.setBasePrice(r.basePrice());
        p.setDurationMinutes(r.durationMinutes()); p.setMaxQuantity(r.maxQuantity()); p.setInstantEligible(r.instantEligible());
        p.setAttributesJson(r.attributesJson() == null || r.attributesJson().isBlank() ? "{}" : r.attributesJson());
        if (r.active() != null) p.setActive(r.active());
        return p;
    }

    // ---- Add-on questions & options ----
    @PostMapping("/questions") public ApiResponse<AddonQuestion> createQuestion(@Valid @RequestBody QuestionReq r) { return ApiResponse.ok(questions.save(apply(new AddonQuestion(), r))); }
    @PutMapping("/questions/{id}") public ApiResponse<AddonQuestion> updateQuestion(@PathVariable Long id, @Valid @RequestBody QuestionReq r) { return ApiResponse.ok(questions.save(apply(find(questions, id, "Question"), r))); }
    @DeleteMapping("/questions/{id}") public ApiResponse<Void> deleteQuestion(@PathVariable Long id) { options.deleteAll(options.findByQuestionId(id)); questions.deleteById(id); return ApiResponse.ok(null); }
    private AddonQuestion apply(AddonQuestion q, QuestionReq r) {
        q.setPackageId(r.packageId()); q.setQuestionKey(r.questionKey()); q.setQuestionText(r.questionText()); q.setType(r.type()); q.setRequired(r.required()); q.setSortOrder(r.sortOrder());
        return q;
    }
    @PostMapping("/options") public ApiResponse<AddonOption> createOption(@Valid @RequestBody OptionReq r) { return ApiResponse.ok(options.save(apply(new AddonOption(), r))); }
    @PutMapping("/options/{id}") public ApiResponse<AddonOption> updateOption(@PathVariable Long id, @Valid @RequestBody OptionReq r) { return ApiResponse.ok(options.save(apply(find(options, id, "Option"), r))); }
    @DeleteMapping("/options/{id}") public ApiResponse<Void> deleteOption(@PathVariable Long id) { find(options, id, "Option").setActive(false); return ApiResponse.ok(null); }
    private AddonOption apply(AddonOption o, OptionReq r) {
        o.setQuestionId(r.questionId()); o.setOptionKey(r.optionKey()); o.setLabel(r.label()); o.setPriceDelta(r.priceDelta()); o.setDurationDeltaMinutes(r.durationDeltaMinutes());
        if (r.active() != null) o.setActive(r.active());
        return o;
    }

    // ---- Rate cards (spare parts) ----
    @PostMapping("/rate-cards") public ApiResponse<RateCard> createRate(@Valid @RequestBody RateCardReq r) { return ApiResponse.ok(rateCards.save(apply(new RateCard(), r))); }
    @PutMapping("/rate-cards/{id}") public ApiResponse<RateCard> updateRate(@PathVariable Long id, @Valid @RequestBody RateCardReq r) { return ApiResponse.ok(rateCards.save(apply(find(rateCards, id, "Rate card"), r))); }
    @DeleteMapping("/rate-cards/{id}") public ApiResponse<Void> deleteRate(@PathVariable Long id) { find(rateCards, id, "Rate card").setActive(false); return ApiResponse.ok(null); }
    private RateCard apply(RateCard c, RateCardReq r) {
        c.setServiceId(r.serviceId()); c.setItemName(r.itemName()); c.setPrice(r.price()); c.setUnit(r.unit() == null ? "piece" : r.unit()); c.setDescription(r.description());
        if (r.active() != null) c.setActive(r.active());
        return c;
    }
}

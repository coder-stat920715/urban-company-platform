package com.urbancompany.platform.catalog;

import com.urbancompany.platform.catalog.CatalogViews.*;
import com.urbancompany.platform.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CatalogService {
    private final CategoryRepository categories;
    private final ServiceOfferingRepository services;
    private final ServicePackageRepository packages;
    private final AddonQuestionRepository questions;
    private final AddonOptionRepository options;
    private final RateCardRepository rateCards;
    private final PackageAssembler assembler;

    public List<CategoryNode> tree() {
        List<Category> all = categories.findByActiveTrueOrderBySortOrderAscNameAsc();
        Map<Long, List<Category>> byParent = all.stream().filter(c -> c.getParentId() != null).collect(Collectors.groupingBy(Category::getParentId));
        return all.stream().filter(c -> c.getParentId() == null).map(c -> node(c, byParent)).toList();
    }

    private CategoryNode node(Category c, Map<Long, List<Category>> byParent) {
        return new CategoryNode(c.getId(), c.getName(), c.getSlug(), c.getIconUrl(),
                byParent.getOrDefault(c.getId(), List.of()).stream().map(ch -> node(ch, byParent)).toList());
    }

    public List<ServiceSummary> servicesByCategory(Long categoryId) {
        return services.findByCategoryIdAndActiveTrueOrderByName(categoryId).stream().map(s -> {
            var min = packages.findByServiceIdAndActiveTrueOrderByBasePrice(s.getId()).stream().findFirst().map(ServicePackage::getBasePrice).orElse(null);
            return new ServiceSummary(s.getId(), s.getName(), s.getSlug(), s.getDescription(), min);
        }).toList();
    }

    public ServiceDetail serviceDetail(Long serviceId) {
        ServiceOffering s = services.findById(serviceId).filter(ServiceOffering::isActive).orElseThrow(() -> BusinessException.notFound("Service"));
        Category cat = categories.findById(s.getCategoryId()).orElseThrow();
        List<ServicePackage> pk = packages.findByServiceIdAndActiveTrueOrderByBasePrice(serviceId);
        List<PackageDetail> details = pk.stream().map(p -> detail(p, cat)).toList();
        List<RateCardView> rc = rateCards.findByServiceIdAndActiveTrueOrderByItemName(serviceId).stream()
                .map(r -> new RateCardView(r.getId(), r.getItemName(), r.getPrice(), r.getUnit(), r.getDescription())).toList();
        return new ServiceDetail(s.getId(), s.getName(), s.getDescription(), cat.getId(), details, rc);
    }

    /** Used by cart/booking: full package detail with category resolved. */
    public PackageDetail packageDetail(Long packageId) {
        ServicePackage p = packages.findById(packageId).filter(ServicePackage::isActive).orElseThrow(() -> BusinessException.notFound("Package"));
        ServiceOffering s = services.findById(p.getServiceId()).orElseThrow();
        return detail(p, categories.findById(s.getCategoryId()).orElseThrow());
    }

    public String categoryName(Long categoryId) { return categories.findById(categoryId).map(Category::getName).orElse("Category"); }

    private PackageDetail detail(ServicePackage p, Category cat) {
        List<AddonQuestion> qs = questions.findByPackageIdOrderBySortOrder(p.getId());
        Map<Long, List<AddonOption>> opts = qs.isEmpty() ? Map.of()
                : options.findByQuestionIdInAndActiveTrue(qs.stream().map(AddonQuestion::getId).toList()).stream().collect(Collectors.groupingBy(AddonOption::getQuestionId));
        return assembler.assemble(p, cat.getId(), cat.getName(), qs, opts);
    }
}

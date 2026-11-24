package com.urbancompany.platform.catalog;

import com.urbancompany.platform.catalog.CatalogViews.*;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Factory + Builder: assembles a package with its dynamic add-on questions/options into a single read model. */
@Component
public class PackageAssembler {
    public PackageDetail assemble(ServicePackage p, Long categoryId, String categoryName,
                                  List<AddonQuestion> questions, Map<Long, List<AddonOption>> optionsByQuestion) {
        List<QuestionView> qv = questions.stream().sorted(Comparator.comparingInt(AddonQuestion::getSortOrder))
                .map(q -> new QuestionView(q.getId(), q.getQuestionKey(), q.getQuestionText(), q.getType(), q.isRequired(),
                        optionsByQuestion.getOrDefault(q.getId(), List.of()).stream()
                                .map(o -> new OptionView(o.getId(), o.getOptionKey(), o.getLabel(), o.getPriceDelta(), o.getDurationDeltaMinutes())).toList()))
                .toList();
        return PackageDetail.builder().id(p.getId()).serviceId(p.getServiceId()).categoryId(categoryId).categoryName(categoryName)
                .name(p.getName()).description(p.getDescription()).basePrice(p.getBasePrice()).durationMinutes(p.getDurationMinutes())
                .maxQuantity(p.getMaxQuantity()).instantEligible(p.isInstantEligible()).attributesJson(p.getAttributesJson()).questions(qv).build();
    }
}

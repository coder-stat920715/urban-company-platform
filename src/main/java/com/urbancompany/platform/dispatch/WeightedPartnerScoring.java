package com.urbancompany.platform.dispatch;

import com.urbancompany.platform.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** score = wD*(1 - dist/radius) + wR*(rating/5) + wA*acceptanceRate + wW*(1 - activeJobs/maxConcurrent). */
@Component
@RequiredArgsConstructor
public class WeightedPartnerScoring implements PartnerScoringStrategy {
    private final AppProperties props;

    @Override
    public double score(Candidate c) {
        var d = props.dispatch();
        double dist = 1.0 - Math.min(c.distanceKm() / Math.max(c.maxRadiusKm(), 0.1), 1.0);
        double rating = c.partner().getRatingCount() == 0 ? 0.8 : Math.min(c.partner().getRatingAvg() / 5.0, 1.0);   // new partners get a fair default
        double accept = c.partner().getAcceptanceRate();
        double load = 1.0 - Math.min((double) c.partner().getActiveJobs() / Math.max(d.maxConcurrentJobs(), 1), 1.0);
        return d.weightDistance() * dist + d.weightRating() * rating + d.weightAcceptance() * accept + d.weightWorkload() * load;
    }
}

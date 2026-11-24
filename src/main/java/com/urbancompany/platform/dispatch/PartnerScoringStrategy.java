package com.urbancompany.platform.dispatch;

/** Strategy pattern for partner ranking; higher score = better. Swap implementations via configuration/@Primary. */
public interface PartnerScoringStrategy {
    double score(Candidate c);
}

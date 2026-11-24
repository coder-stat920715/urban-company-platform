package com.urbancompany.platform.dispatch;

import com.urbancompany.platform.partner.Partner;

public record Candidate(Partner partner, double distanceKm, double maxRadiusKm) {}

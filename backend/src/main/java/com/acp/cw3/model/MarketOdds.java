package com.acp.cw3.model;

import java.time.Instant;
import java.util.List;

public record MarketOdds(
        String source,
        double demProbability,
        double repProbability,
        Instant lastUpdated,
        List<PrimaryCandidateOdds> republicanPrimary,
        List<PrimaryCandidateOdds> democraticPrimary
) {
    public MarketOdds(String source, double demProbability, double repProbability, Instant lastUpdated) {
        this(source, demProbability, repProbability, lastUpdated, List.of(), List.of());
    }
}

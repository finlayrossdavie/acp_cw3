package com.acp.cw3.model;

import java.time.Instant;
import java.util.List;

public record Race(
        String raceId,
        String state,
        String officeType,
        List<PollEntry> polls,
        Projection projection,
        String leadingParty,
        double margin,
        String color,
        List<NewsArticle> news,
        MarketOdds odds,
        MarketOdds kalshiOdds,
        List<CandidateSpending> spending,
        Instant updatedAt,
        String sourceType
) {
}

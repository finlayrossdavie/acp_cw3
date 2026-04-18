package com.acp.cw3.dto;

import java.time.Instant;

public record StateSummaryDto(
        String state,
        String leadingParty,
        double margin,
        String color,
        Instant updatedAt
) {
}

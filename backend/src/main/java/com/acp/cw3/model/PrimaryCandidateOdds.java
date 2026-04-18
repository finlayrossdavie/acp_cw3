package com.acp.cw3.model;

public record PrimaryCandidateOdds(
        String candidate,
        String party,
        double probability
) {
}

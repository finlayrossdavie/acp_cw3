package com.acp.cw3.model;

public record CandidateSpending(
        String candidateId,
        String candidateName,
        String party,
        double committeeDisbursements,
        double independentSupport,
        double totalFor
) {
}


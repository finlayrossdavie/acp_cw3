package com.acp.cw3.service;

import com.acp.cw3.model.CandidateSpending;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class OpenFecClient {
    private static final int TARGET_CYCLE = 2026;
    private static final int CANDIDATE_TOTALS_PER_PAGE = 20;
    private static final int MAX_CANDIDATES_TO_RETURN = 8;

    private final RestClient restClient;
    private final String apiKey;
    private final String baseUrl;

    public OpenFecClient(
            RestClient restClient,
            @Value("${app.finance.openfec-key:${OPEN_FEC_API_KEY:}}") String apiKey,
            @Value("${app.finance.openfec-base-url:https://api.open.fec.gov/v1}") String baseUrl
    ) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    public List<CandidateSpending> fetchStateSenateSpending(String stateAbbr) {
        if (apiKey == null || apiKey.isBlank() || stateAbbr == null || stateAbbr.isBlank()) {
            return List.of();
        }

        String state = stateAbbr.trim().toUpperCase(Locale.ROOT);
        JsonNode candidateTotalsResponse = get("/candidates/totals/",
                "api_key=" + encode(apiKey)
                        + "&state=" + encode(state)
                        + "&office=S"
                        + "&cycle=" + TARGET_CYCLE
                        + "&sort=-receipts"
                        + "&per_page=" + CANDIDATE_TOTALS_PER_PAGE);
        if (candidateTotalsResponse == null || !candidateTotalsResponse.path("results").isArray()) {
            return List.of();
        }

        List<CandidateSpending> out = new ArrayList<>();
        int kept = 0;
        for (JsonNode candidate : candidateTotalsResponse.path("results")) {
            if (kept >= MAX_CANDIDATES_TO_RETURN) {
                break;
            }
            if (!candidate.path("has_raised_funds").asBoolean(false)) {
                continue;
            }
            String candidateId = candidate.path("candidate_id").asText("");
            if (candidateId.isBlank()) {
                continue;
            }

            String status = candidate.path("candidate_status").asText("");
            if (!"C".equalsIgnoreCase(status)) {
                continue;
            }

            String name = candidate.path("name").asText(candidateId);
            String party = candidate.path("party").asText("OTHER").toUpperCase(Locale.ROOT);

            double committeeDisbursements = candidate.path("disbursements").asDouble(0.0);
            // Keep this field in the model for compatibility, but do not blend it into the
            // displayed total so values align with FEC candidate financial totals.
            double independentSupport = 0.0;
            double totalFor = committeeDisbursements;

            if (totalFor <= 0.0) {
                continue;
            }

            out.add(new CandidateSpending(
                    candidateId,
                    name,
                    party,
                    committeeDisbursements,
                    independentSupport,
                    totalFor
            ));
            kept++;
        }

        return out.stream()
                .sorted(Comparator.comparingDouble(CandidateSpending::totalFor).reversed())
                .toList();
    }

    private JsonNode get(String path, String query) {
        String url = baseUrl + path + "?" + query;
        return restClient.get()
                .uri(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; CW3-MidTerm-Tracker/1.0)")
                .retrieve()
                .body(JsonNode.class);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}


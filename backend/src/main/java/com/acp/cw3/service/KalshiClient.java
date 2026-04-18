package com.acp.cw3.service;

import com.acp.cw3.model.MarketOdds;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class KalshiClient {
    private static final Logger log = LoggerFactory.getLogger(KalshiClient.class);

    private final RestClient restClient;
    private final boolean enabled;
    private final String marketsApiBaseUrl;

    public KalshiClient(
            RestClient restClient,
            @Value("${app.kalshi.enabled:true}") boolean enabled,
            @Value("${app.kalshi.markets-api-base-url:https://api.elections.kalshi.com/trade-api/v2/markets}") String marketsApiBaseUrl
    ) {
        this.restClient = restClient;
        this.enabled = enabled;
        this.marketsApiBaseUrl = marketsApiBaseUrl;
    }

    public Map<String, MarketOdds> fetchOddsByState() {
        if (!enabled) {
            return Map.of();
        }
        Map<String, String> seriesByState = loadSeriesTickerByState();
        if (seriesByState.isEmpty()) {
            return Map.of();
        }

        Map<String, MarketOdds> byState = new HashMap<>();
        for (Map.Entry<String, String> entry : seriesByState.entrySet()) {
            String state = entry.getKey();
            String seriesTicker = entry.getValue();
            try {
                String url = marketsApiBaseUrl
                        + "?series_ticker=" + seriesTicker
                        + "&status=open"
                        + "&limit=200";
                JsonNode response = restClient.get().uri(url).retrieve().body(JsonNode.class);
                if (response == null || !response.path("markets").isArray()) {
                    continue;
                }
                Double dem = null;
                Double rep = null;
                for (JsonNode market : response.path("markets")) {
                    String ticker = market.path("ticker").asText("").toUpperCase(Locale.ROOT);
                    double yesPrice = market.path("yes_ask_dollars").asDouble(-1.0);
                    if (yesPrice < 0.0 || yesPrice > 1.0) {
                        yesPrice = market.path("last_price_dollars").asDouble(-1.0);
                    }
                    if (yesPrice < 0.0 || yesPrice > 1.0) {
                        continue;
                    }
                    if (ticker.endsWith("-D")) {
                        dem = yesPrice;
                    } else if (ticker.endsWith("-R")) {
                        rep = yesPrice;
                    }
                }
                if (dem == null && rep == null) {
                    continue;
                }
                if (dem == null) {
                    dem = 1.0 - rep;
                }
                if (rep == null) {
                    rep = 1.0 - dem;
                }
                byState.put(state, new MarketOdds("KALSHI", clamp(dem), clamp(rep), Instant.now()));
            } catch (Exception ex) {
                log.warn("Kalshi odds fetch failed for {}: {}", state, ex.getMessage());
            }
        }
        return byState;
    }

    private Map<String, String> loadSeriesTickerByState() {
        try {
            var resource = new ClassPathResource("kalshi_links.csv");
            String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> out = new HashMap<>();
            String[] lines = text.split("\\R");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] cols = line.split(",", 4);
                if (cols.length < 2) {
                    continue;
                }
                String stateName = cols[0].trim();
                String overallUrl = cols[1].trim();
                if (overallUrl.isEmpty() || "null".equalsIgnoreCase(overallUrl)) {
                    continue;
                }
                String state = stateToAbbr(stateName);
                if (state == null) {
                    continue;
                }
                String[] urlParts = overallUrl.split("/");
                if (urlParts.length < 5) {
                    continue;
                }
                String seriesTicker = urlParts[4].trim().toUpperCase(Locale.ROOT);
                if (!seriesTicker.isBlank()) {
                    out.put(state, seriesTicker);
                }
            }
            return out;
        } catch (Exception ex) {
            log.warn("kalshi_links.csv not readable: {}", ex.getMessage());
            return Map.of();
        }
    }

    private String stateToAbbr(String stateName) {
        Map<String, String> map = Map.ofEntries(
                Map.entry("Alabama", "AL"), Map.entry("Alaska", "AK"), Map.entry("Arkansas", "AR"),
                Map.entry("Colorado", "CO"), Map.entry("Delaware", "DE"), Map.entry("Florida", "FL"),
                Map.entry("Georgia", "GA"), Map.entry("Idaho", "ID"), Map.entry("Illinois", "IL"),
                Map.entry("Iowa", "IA"), Map.entry("Kansas", "KS"), Map.entry("Kentucky", "KY"),
                Map.entry("Louisiana", "LA"), Map.entry("Maine", "ME"), Map.entry("Massachusetts", "MA"),
                Map.entry("Michigan", "MI"), Map.entry("Minnesota", "MN"), Map.entry("Mississippi", "MS"),
                Map.entry("Montana", "MT"), Map.entry("Nebraska", "NE"), Map.entry("New Hampshire", "NH"),
                Map.entry("New Jersey", "NJ"), Map.entry("New Mexico", "NM"), Map.entry("North Carolina", "NC"),
                Map.entry("Ohio", "OH"), Map.entry("Oklahoma", "OK"), Map.entry("Oregon", "OR"),
                Map.entry("Rhode Island", "RI"), Map.entry("South Carolina", "SC"), Map.entry("South Dakota", "SD"),
                Map.entry("Tennessee", "TN"), Map.entry("Texas", "TX"), Map.entry("Virginia", "VA"),
                Map.entry("West Virginia", "WV"), Map.entry("Wyoming", "WY")
        );
        return map.get(stateName);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}


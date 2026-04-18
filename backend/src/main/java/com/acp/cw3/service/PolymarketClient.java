package com.acp.cw3.service;

import com.acp.cw3.model.MarketOdds;
import com.acp.cw3.model.PrimaryCandidateOdds;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PolymarketClient {
    private static final Logger log = LoggerFactory.getLogger(PolymarketClient.class);
    private static final double PRIMARY_MIN_PROBABILITY = 0.05;
    private static final Pattern CANDIDATE_FROM_WIN_QUESTION = Pattern.compile("(?i)^\\s*Will\\s+(.+?)\\s+win\\b");
    private static final Pattern CANDIDATE_FROM_NOMINEE_QUESTION = Pattern.compile("(?i)^\\s*Will\\s+(.+?)\\s+be\\s+the\\b");

    // Best-effort mapping for states covered by backend `states.txt`.
    // This allows keyword-based state resolution even when env-provided mappings are partial.
    private static final Map<String, String> STATE_NAME_TO_ABBR = Map.ofEntries(
            Map.entry("Alabama", "AL"),
            Map.entry("Alaska", "AK"),
            Map.entry("Arkansas", "AR"),
            Map.entry("Colorado", "CO"),
            Map.entry("Delaware", "DE"),
            Map.entry("Florida", "FL"),
            Map.entry("Georgia", "GA"),
            Map.entry("Idaho", "ID"),
            Map.entry("Illinois", "IL"),
            Map.entry("Iowa", "IA"),
            Map.entry("Kansas", "KS"),
            Map.entry("Kentucky", "KY"),
            Map.entry("Louisiana", "LA"),
            Map.entry("Maine", "ME"),
            Map.entry("Massachusetts", "MA"),
            Map.entry("Michigan", "MI"),
            Map.entry("Minnesota", "MN"),
            Map.entry("Mississippi", "MS"),
            Map.entry("Montana", "MT"),
            Map.entry("Nebraska", "NE"),
            Map.entry("New Hampshire", "NH"),
            Map.entry("New Jersey", "NJ"),
            Map.entry("New Mexico", "NM"),
            Map.entry("North Carolina", "NC"),
            Map.entry("Ohio", "OH"),
            Map.entry("Oklahoma", "OK"),
            Map.entry("Oregon", "OR"),
            Map.entry("Rhode Island", "RI"),
            Map.entry("South Carolina", "SC"),
            Map.entry("South Dakota", "SD"),
            Map.entry("Tennessee", "TN"),
            Map.entry("Texas", "TX"),
            Map.entry("Virginia", "VA"),
            Map.entry("West Virginia", "WV"),
            Map.entry("Wyoming", "WY")
    );

    private final RestClient restClient;
    private final boolean enabled;
    private final String baseUrl;
    private final String eventMapRaw;
    private final String marketMapRaw;
    private final ObjectMapper objectMapper;

    public PolymarketClient(
            RestClient restClient,
            @Value("${app.polymarket.enabled:true}") boolean enabled,
            @Value("${app.polymarket.base-url}") String baseUrl,
            @Value("${app.polymarket.event-map:}") String eventMapRaw,
            @Value("${app.polymarket.market-map:}") String marketMapRaw
    ) {
        this.restClient = restClient;
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.eventMapRaw = eventMapRaw;
        this.marketMapRaw = marketMapRaw;
        this.objectMapper = new ObjectMapper();
    }

    public Map<String, MarketOdds> fetchOddsByState() {
        if (!enabled) {
            return Map.of();
        }
        Map<String, MarketOdds> oddsByState = new HashMap<>();

        // 1) If we have explicit event slugs, fetch those first.
        try {
            Map<String, String> eventMapFromEnv = parseMap(eventMapRaw);
            Map<String, String> eventMapFromFile = loadEventMapFromLinksFile();
            Map<String, String> eventMapFromPrimaryCsv = loadOverallEventMapFromPrimaryCsv();

            Map<String, String> merged = new HashMap<>(eventMapFromFile);
            merged.putAll(eventMapFromPrimaryCsv);
            merged.putAll(eventMapFromEnv); // env overrides file

            if (!merged.isEmpty()) {
                oddsByState.putAll(fetchOddsFromEventSlugs(merged));
            }
        } catch (Exception ex) {
            log.warn("Polymarket event-slug fetch failed: {}", ex.getMessage());
        }

        // 2) Also try markets listing as a best-effort fill for any states not covered by event-map.
        try {
            JsonNode response = restClient.get().uri(baseUrl).retrieve().body(JsonNode.class);
            if (response == null) {
                return oddsByState;
            }
            JsonNode marketsNode = response.isArray() ? response : response.path("data");
            if (!marketsNode.isArray()) {
                return oddsByState;
            }

            Map<String, String> marketMap = parseMarketMap();

            for (JsonNode market : marketsNode) {
                String title = market.path("question").asText("");
                if (title.isBlank()) {
                    title = market.path("title").asText("");
                }
                if (title.isBlank()) {
                    continue;
                }

                String state = resolveState(title, marketMap);
                if (state == null) {
                    continue;
                }
                if (oddsByState.containsKey(state)) {
                    continue; // Prefer event-map odds if present
                }

                double yesPrice = extractYesPrice(market);
                if (yesPrice < 0.0 || yesPrice > 1.0) {
                    continue;
                }

                String normalized = title.toLowerCase(Locale.ROOT);
                boolean yesIsRep = normalized.contains("republican")
                        || normalized.contains("gop")
                        || normalized.contains("(r)")
                        || normalized.contains("rep ");
                double repProbability = yesIsRep ? yesPrice : (1.0 - yesPrice);
                double demProbability = 1.0 - repProbability;
                oddsByState.put(state, new MarketOdds("POLYMARKET", demProbability, repProbability, Instant.now()));
            }
        } catch (Exception ex) {
            log.warn("Polymarket markets fetch failed (continuing): {}", ex.getMessage());
        }

        enrichWithPrimaryOdds(oddsByState);
        return oddsByState;
    }

    private void enrichWithPrimaryOdds(Map<String, MarketOdds> oddsByState) {
        Map<String, PrimaryLinks> linksByState = loadPrimaryLinksByStateFromCsv();
        Map<String, String> eventMapFromEnv = parseMap(eventMapRaw);
        Map<String, String> eventMapFromFile = loadEventMapFromLinksFile();
        Map<String, String> mergedEventMap = new HashMap<>(eventMapFromFile);
        mergedEventMap.putAll(eventMapFromEnv);
        for (Map.Entry<String, PrimaryLinks> entry : linksByState.entrySet()) {
            String state = entry.getKey();
            MarketOdds overall = oddsByState.get(state);
            if (overall == null) {
                continue; // Keep map behavior tied to overall market odds.
            }
            PrimaryLinks links = entry.getValue();
            List<PrimaryCandidateOdds> repPrimary = fetchPrimaryCandidates(links.repPrimarySlug(), "REP");
            List<PrimaryCandidateOdds> demPrimary = fetchPrimaryCandidates(links.demPrimarySlug(), "DEM");
            if (repPrimary.isEmpty() && demPrimary.isEmpty()) {
                Map<String, List<PrimaryCandidateOdds>> overrides = fetchAlaskaSpecialPrimaryOdds(state, mergedEventMap.get(state));
                repPrimary = overrides.getOrDefault("REP", List.of());
                demPrimary = overrides.getOrDefault("DEM", List.of());
            }
            oddsByState.put(state, new MarketOdds(
                    overall.source(),
                    overall.demProbability(),
                    overall.repProbability(),
                    overall.lastUpdated(),
                    repPrimary,
                    demPrimary
            ));
        }
    }

    private Map<String, List<PrimaryCandidateOdds>> fetchAlaskaSpecialPrimaryOdds(String state, String fallbackEventSlug) {
        if (!"AK".equalsIgnoreCase(state)) {
            return Map.of();
        }
        String eventSlug = (fallbackEventSlug == null || fallbackEventSlug.isBlank())
                ? "alaska-senate-election-winner"
                : fallbackEventSlug;
        Map<String, String> requiredCandidates = new LinkedHashMap<>();
        requiredCandidates.put("Dan Sullivan", "REP");
        requiredCandidates.put("Mary Peltola", "DEM");
        return fetchSelectedCandidatesFromEvent(eventSlug, requiredCandidates);
    }

    private Map<String, List<PrimaryCandidateOdds>> fetchSelectedCandidatesFromEvent(
            String eventSlug,
            Map<String, String> requiredCandidates
    ) {
        if (eventSlug == null || eventSlug.isBlank() || requiredCandidates.isEmpty()) {
            return Map.of();
        }
        List<PrimaryCandidateOdds> rep = new ArrayList<>();
        List<PrimaryCandidateOdds> dem = new ArrayList<>();
        try {
            JsonNode eventNode = restClient.get()
                    .uri(baseUrl.endsWith("/") ? (baseUrl + eventSlug) : (baseUrl + "/" + eventSlug))
                    .retrieve()
                    .body(JsonNode.class);
            if (eventNode == null) {
                return Map.of();
            }
            JsonNode marketsNode = eventNode.path("markets");
            if (!marketsNode.isArray()) {
                return Map.of();
            }
            for (JsonNode market : marketsNode) {
                if (market.has("active") && !market.path("active").asBoolean(true)) {
                    continue;
                }
                String candidateName = market.path("groupItemTitle").asText("").trim();
                if (candidateName.isBlank()) {
                    candidateName = extractCandidateName(market);
                }
                if (candidateName == null || candidateName.isBlank()) {
                    continue;
                }
                String party = requiredCandidates.get(candidateName);
                if (party == null) {
                    continue;
                }
                double yesPrice = extractYesPrice(market);
                if (yesPrice < 0.0 || yesPrice > 1.0) {
                    continue;
                }
                PrimaryCandidateOdds odds = new PrimaryCandidateOdds(candidateName, party, clamp(yesPrice));
                if ("REP".equalsIgnoreCase(party)) {
                    rep.add(odds);
                } else if ("DEM".equalsIgnoreCase(party)) {
                    dem.add(odds);
                }
            }
            rep.sort(Comparator.comparing(PrimaryCandidateOdds::probability).reversed());
            dem.sort(Comparator.comparing(PrimaryCandidateOdds::probability).reversed());
            return Map.of("REP", rep, "DEM", dem);
        } catch (Exception ex) {
            log.warn("Polymarket special primary fetch failed for slug {}: {}", eventSlug, ex.getMessage());
            return Map.of();
        }
    }

    private List<PrimaryCandidateOdds> fetchPrimaryCandidates(String slug, String party) {
        if (slug == null || slug.isBlank()) {
            return List.of();
        }
        try {
            JsonNode eventNode = restClient.get()
                    .uri(baseUrl.endsWith("/") ? (baseUrl + slug) : (baseUrl + "/" + slug))
                    .retrieve()
                    .body(JsonNode.class);
            if (eventNode == null) {
                return List.of();
            }
            JsonNode marketsNode = eventNode.path("markets");
            if (!marketsNode.isArray()) {
                return List.of();
            }
            List<PrimaryCandidateOdds> candidates = new ArrayList<>();
            for (JsonNode market : marketsNode) {
                if (market.has("active") && !market.path("active").asBoolean(true)) {
                    continue;
                }
                double yesPrice = extractYesPrice(market);
                if (yesPrice < PRIMARY_MIN_PROBABILITY || yesPrice > 1.0) {
                    continue;
                }
                String candidate = extractCandidateName(market);
                if (candidate == null || candidate.isBlank()) {
                    continue;
                }
                candidates.add(new PrimaryCandidateOdds(candidate, party, clamp(yesPrice)));
            }
            return candidates.stream()
                    .sorted(Comparator.comparing(PrimaryCandidateOdds::probability).reversed())
                    .toList();
        } catch (Exception ex) {
            log.warn("Polymarket primary fetch failed for slug {}: {}", slug, ex.getMessage());
            return List.of();
        }
    }

    private String extractCandidateName(JsonNode market) {
        String groupItemTitle = market.path("groupItemTitle").asText("").trim();
        if (!groupItemTitle.isBlank()) {
            String lowered = groupItemTitle.toLowerCase(Locale.ROOT);
            if (!"other".equals(lowered) && !lowered.startsWith("person ")) {
                return groupItemTitle;
            }
        }

        String question = market.path("question").asText("");
        if (question == null || question.isBlank()) {
            return null;
        }
        String trimmed = question.trim();

        Matcher winMatcher = CANDIDATE_FROM_WIN_QUESTION.matcher(trimmed);
        if (winMatcher.find()) {
            return winMatcher.group(1).trim();
        }

        Matcher nomineeMatcher = CANDIDATE_FROM_NOMINEE_QUESTION.matcher(trimmed);
        if (nomineeMatcher.find()) {
            return nomineeMatcher.group(1).trim();
        }
        return null;
    }

    private record PrimaryLinks(String repPrimarySlug, String demPrimarySlug) {}

    private Map<String, PrimaryLinks> loadPrimaryLinksByStateFromCsv() {
        try {
            var resource = new ClassPathResource("polymarket_links.csv");
            byte[] bytes = resource.getInputStream().readAllBytes();
            String text = new String(bytes, StandardCharsets.UTF_8);
            Map<String, PrimaryLinks> links = new HashMap<>();
            String[] lines = text.split("\\R");
            // Skip header line.
            for (int i = 1; i < lines.length; i++) {
                String raw = lines[i].trim();
                if (raw.isEmpty()) {
                    continue;
                }
                String[] cols = raw.split(",", 4);
                if (cols.length < 4) {
                    continue;
                }
                String stateName = cols[0].trim();
                String repUrl = cols[2].trim();
                String demUrl = cols[3].trim();
                String state = resolveStateAbbrFromStateName(stateName);
                if (state == null) {
                    continue;
                }
                String repSlug = "null".equalsIgnoreCase(repUrl) ? null : extractSlugFromPolymarketEventUrl(repUrl);
                String demSlug = "null".equalsIgnoreCase(demUrl) ? null : extractSlugFromPolymarketEventUrl(demUrl);
                links.put(state, new PrimaryLinks(repSlug, demSlug));
            }
            return links;
        } catch (Exception ex) {
            log.warn("Primary links CSV not readable (continuing): {}", ex.getMessage());
            return Map.of();
        }
    }

    private Map<String, String> loadEventMapFromLinksFile() {
        try {
            var resource = new ClassPathResource("polymarket_overall_winner_links.txt");
            byte[] bytes = resource.getInputStream().readAllBytes();
            String text = new String(bytes, StandardCharsets.UTF_8);

            Map<String, String> mapping = new HashMap<>();
            for (String line : text.split("\\R")) {
                String raw = line.trim();
                if (raw.isEmpty()) {
                    continue;
                }
                String[] parts = raw.split(":", 2);
                if (parts.length != 2) {
                    continue;
                }

                String stateName = parts[0].trim();
                String url = parts[1].trim();

                String stateAbbr = resolveStateAbbrFromStateName(stateName);
                if (stateAbbr == null) {
                    continue;
                }

                String slug = extractSlugFromPolymarketEventUrl(url);
                if (slug == null || slug.isBlank()) {
                    continue;
                }

                mapping.put(stateAbbr, slug);
            }
            return mapping;
        } catch (IOException ex) {
            log.warn("Polymarket links file not readable (continuing): {}", ex.getMessage());
            return Map.of();
        }
    }

    private Map<String, String> loadOverallEventMapFromPrimaryCsv() {
        try {
            var resource = new ClassPathResource("polymarket_links.csv");
            byte[] bytes = resource.getInputStream().readAllBytes();
            String text = new String(bytes, StandardCharsets.UTF_8);
            Map<String, String> mapping = new HashMap<>();
            String[] lines = text.split("\\R");
            for (int i = 1; i < lines.length; i++) {
                String raw = lines[i].trim();
                if (raw.isEmpty()) {
                    continue;
                }
                String[] cols = raw.split(",", 4);
                if (cols.length < 2) {
                    continue;
                }
                String stateAbbr = resolveStateAbbrFromStateName(cols[0].trim());
                if (stateAbbr == null) {
                    continue;
                }
                String overallUrl = cols[1].trim();
                if (overallUrl.isEmpty() || "null".equalsIgnoreCase(overallUrl)) {
                    continue;
                }
                String slug = extractSlugFromPolymarketEventUrl(overallUrl);
                if (slug == null || slug.isBlank()) {
                    continue;
                }
                mapping.put(stateAbbr, slug);
            }
            return mapping;
        } catch (Exception ex) {
            log.warn("Polymarket primary CSV overall links not readable (continuing): {}", ex.getMessage());
            return Map.of();
        }
    }

    private String resolveStateAbbrFromStateName(String stateName) {
        if (stateName == null || stateName.isBlank()) {
            return null;
        }
        String normalized = stateName.replaceAll("\\(.*\\)", "").trim();
        for (Map.Entry<String, String> entry : STATE_NAME_TO_ABBR.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String extractSlugFromPolymarketEventUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        trimmed = trimmed.split("[?#]")[0];

        // Expected: https://polymarket.com/event/<slug>
        int idx = trimmed.indexOf("/event/");
        String slugPart;
        if (idx >= 0) {
            slugPart = trimmed.substring(idx + "/event/".length());
        } else {
            int lastSlash = trimmed.lastIndexOf('/');
            slugPart = lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
        }

        slugPart = slugPart.replaceAll("^/+", "").replaceAll("/+$", "");
        return slugPart;
    }

    private Map<String, MarketOdds> fetchOddsFromEventSlugs(Map<String, String> eventMap) {
        Map<String, MarketOdds> oddsByState = new HashMap<>();
        for (Map.Entry<String, String> entry : eventMap.entrySet()) {
            String state = entry.getKey();
            String slug = entry.getValue();
            try {
                JsonNode eventNode = restClient.get()
                        .uri(baseUrl.endsWith("/") ? (baseUrl + slug) : (baseUrl + "/" + slug))
                        .retrieve()
                        .body(JsonNode.class);
                if (eventNode == null) {
                    continue;
                }
                MarketOdds odds = extractFromEvent(state, eventNode);
                if (odds != null) {
                    oddsByState.put(state, odds);
                }
            } catch (Exception ex) {
                log.warn("Polymarket event fetch failed for state {} slug {}: {}", state, slug, ex.getMessage());
            }
        }
        return oddsByState;
    }

    private MarketOdds extractFromEvent(String state, JsonNode eventNode) {
        JsonNode marketsNode = eventNode.path("markets");
        if (!marketsNode.isArray()) {
            return null;
        }
        Double dem = null;
        Double rep = null;
        for (JsonNode market : marketsNode) {
            if (market.has("active") && !market.path("active").asBoolean(true)) {
                continue;
            }
            String question = market.path("question").asText("").toLowerCase(Locale.ROOT);
            double yesPrice = extractYesPrice(market);
            if (yesPrice < 0.0 || yesPrice > 1.0) {
                continue;
            }
            if (question.contains("democrat")) {
                dem = yesPrice;
            } else if (question.contains("republican") || question.contains("gop")) {
                rep = yesPrice;
            }
        }
        if ("AK".equalsIgnoreCase(state) && (dem == null || rep == null)) {
            for (JsonNode market : marketsNode) {
                if (market.has("active") && !market.path("active").asBoolean(true)) {
                    continue;
                }
                String candidate = market.path("groupItemTitle").asText("").trim();
                double yesPrice = extractYesPrice(market);
                if (yesPrice < 0.0 || yesPrice > 1.0) {
                    continue;
                }
                if ("Mary Peltola".equalsIgnoreCase(candidate)) {
                    dem = yesPrice;
                } else if ("Dan Sullivan".equalsIgnoreCase(candidate)) {
                    rep = yesPrice;
                }
            }
        }
        if (dem == null && rep == null) {
            return null;
        }
        if (dem == null) {
            dem = 1.0 - rep;
        }
        if (rep == null) {
            rep = 1.0 - dem;
        }
        return new MarketOdds("POLYMARKET", clamp(dem), clamp(rep), Instant.now());
    }

    private double extractYesPrice(JsonNode market) {
        JsonNode outcomes = market.path("outcomes");
        if (outcomes.isArray()) {
            for (JsonNode outcome : outcomes) {
                if ("yes".equalsIgnoreCase(outcome.path("name").asText())) {
                    return outcome.path("price").asDouble(-1.0);
                }
            }
        }
        if (outcomes.isTextual()) {
            try {
                List<String> labels = objectMapper.readValue(outcomes.asText(), new TypeReference<>() {});
                List<Double> prices = extractOutcomePriceList(market.path("outcomePrices"));
                if (!labels.isEmpty() && !prices.isEmpty()) {
                    int yesIndex = -1;
                    for (int i = 0; i < labels.size(); i++) {
                        if ("yes".equalsIgnoreCase(labels.get(i))) {
                            yesIndex = i;
                            break;
                        }
                    }
                    if (yesIndex >= 0 && yesIndex < prices.size()) {
                        return prices.get(yesIndex);
                    }
                    return prices.get(0);
                }
            } catch (Exception ignored) {
            }
        }
        JsonNode prices = market.path("outcomePrices");
        if (prices.isArray() && prices.size() > 0) {
            return prices.get(0).asDouble(-1.0);
        }
        if (prices.isTextual()) {
            try {
                List<Double> parsed = objectMapper.readValue(prices.asText(), new TypeReference<>() {});
                if (!parsed.isEmpty()) {
                    return parsed.get(0);
                }
            } catch (Exception ignored) {
            }
        }
        return market.path("yesPrice").asDouble(-1.0);
    }

    private List<Double> extractOutcomePriceList(JsonNode pricesNode) {
        if (pricesNode.isArray()) {
            List<Double> values = new ArrayList<>();
            for (JsonNode node : pricesNode) {
                values.add(node.asDouble(-1.0));
            }
            return values;
        }
        if (pricesNode.isTextual()) {
            try {
                return objectMapper.readValue(pricesNode.asText(), new TypeReference<>() {});
            } catch (Exception ignored) {
            }
        }
        return List.of();
    }

    private Map<String, String> parseMarketMap() {
        Map<String, String> mapping = parseMap(marketMapRaw);
        Map<String, String> normalized = new HashMap<>();
        mapping.forEach((k, v) -> normalized.put(k, v.toLowerCase(Locale.ROOT)));
        return normalized;
    }

    private Map<String, String> parseMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, String> mapping = new HashMap<>();
        for (String entry : raw.split(";")) {
            String[] pair = entry.split("=", 2);
            if (pair.length == 2) {
                mapping.put(pair[0].trim().toUpperCase(Locale.ROOT), pair[1].trim());
            }
        }
        return mapping;
    }

    private String resolveState(String title, Map<String, String> mapping) {
        String normalized = title.toLowerCase(Locale.ROOT);
        if (!normalized.contains("senate")) {
            return null;
        }
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            if (normalized.contains(entry.getValue())) {
                return entry.getKey();
            }
        }
        return resolveStateHeuristic(title);
    }

    private String resolveStateHeuristic(String title) {
        String normalized = title.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : STATE_NAME_TO_ABBR.entrySet()) {
            if (normalized.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}

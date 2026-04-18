package com.acp.cw3.service;

import com.acp.cw3.model.NewsArticle;
import com.acp.cw3.model.PollEntry;
import com.acp.cw3.model.Projection;
import com.acp.cw3.model.Race;
import com.acp.cw3.model.MarketOdds;
import com.acp.cw3.model.CandidateSpending;
import com.acp.cw3.repository.DynamoRaceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class IngestionService {
    private static final Set<String> SENATE_CSV_COLUMNS = Set.of("dem_poll", "rep_poll", "state", "start_date");
    private static final String CURATED_POLL_SOURCE = "SENATE_CSV";
    private record GeneralMatchupSelection(String fieldName, JsonNode polls) {}

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final class NewsRateLimitException extends RuntimeException {
        private NewsRateLimitException(String message) {
            super(message);
        }
    }
    private final RestClient restClient;
    private final GoogleNewsClient googleNewsClient;
    private final ProjectionService projectionService;
    private final PolymarketClient polymarketClient;
    private final KalshiClient kalshiClient;
    private final OpenFecClient openFecClient;
    private final DynamoRaceRepository repository;
    private final RedisCacheService cacheService;
    private final ObjectMapper objectMapper;
    private final String fiveThirtyEightPollUrl;
    private final List<String> states;
    private final int pollLimitPerState;
    private final int newsLimitPerState;
    private final int recencyDays;

    // News is external/fragile (rate limited), so we fetch it on-demand and cache results.
    private static final Duration NEWS_CACHE_TTL = Duration.ofHours(12);
    private static final Duration NEWS_RATE_LIMIT_TTL = Duration.ofHours(1);
    private Map<String, Race> fallbackByStateCache;
    private static final Map<String, String> STATE_NAMES = Map.ofEntries(
            Map.entry("AL", "Alabama"),
            Map.entry("AK", "Alaska"),
            Map.entry("AR", "Arkansas"),
            Map.entry("CO", "Colorado"),
            Map.entry("DE", "Delaware"),
            Map.entry("FL", "Florida"),
            Map.entry("GA", "Georgia"),
            Map.entry("ID", "Idaho"),
            Map.entry("IL", "Illinois"),
            Map.entry("IA", "Iowa"),
            Map.entry("KS", "Kansas"),
            Map.entry("KY", "Kentucky"),
            Map.entry("LA", "Louisiana"),
            Map.entry("ME", "Maine"),
            Map.entry("MA", "Massachusetts"),
            Map.entry("MI", "Michigan"),
            Map.entry("MN", "Minnesota"),
            Map.entry("MS", "Mississippi"),
            Map.entry("MT", "Montana"),
            Map.entry("NE", "Nebraska"),
            Map.entry("NH", "New Hampshire"),
            Map.entry("NJ", "New Jersey"),
            Map.entry("NM", "New Mexico"),
            Map.entry("NC", "North Carolina"),
            Map.entry("OH", "Ohio"),
            Map.entry("OK", "Oklahoma"),
            Map.entry("OR", "Oregon"),
            Map.entry("RI", "Rhode Island"),
            Map.entry("SC", "South Carolina"),
            Map.entry("SD", "South Dakota"),
            Map.entry("TN", "Tennessee"),
            Map.entry("TX", "Texas"),
            Map.entry("VA", "Virginia"),
            Map.entry("WV", "West Virginia"),
            Map.entry("WY", "Wyoming")
    );

    private static final Map<String, String> STATE_ABBR_BY_NAME = buildStateAbbrByName();

    private static Map<String, String> buildStateAbbrByName() {
        Map<String, String> map = new HashMap<>();
        STATE_NAMES.forEach((abbr, name) -> map.put(name.toUpperCase(Locale.ROOT), abbr));
        return Map.copyOf(map);
    }

    public IngestionService(RestClient restClient,
                            GoogleNewsClient googleNewsClient,
                            ProjectionService projectionService,
                            PolymarketClient polymarketClient,
                            KalshiClient kalshiClient,
                            OpenFecClient openFecClient,
                            DynamoRaceRepository repository,
                            RedisCacheService cacheService,
                            @Value("${app.polling.fivethirtyeight-url}") String fiveThirtyEightPollUrl,
                            @Value("${app.polling.recency-days:30}") int recencyDays,
                            @Value("${app.states}") String statesParam,
                            @Value("${app.poll-limit-per-state}") int pollLimitPerState,
                            @Value("${app.news-limit-per-state}") int newsLimitPerState) {
        this.restClient = restClient;
        this.googleNewsClient = googleNewsClient;
        this.projectionService = projectionService;
        this.polymarketClient = polymarketClient;
        this.kalshiClient = kalshiClient;
        this.openFecClient = openFecClient;
        this.repository = repository;
        this.cacheService = cacheService;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.fiveThirtyEightPollUrl = fiveThirtyEightPollUrl;
        this.recencyDays = recencyDays;
        List<String> fromTxt = loadStatesFromTxt();
        this.states = fromTxt.isEmpty()
                ? Arrays.stream(statesParam.split(",")).map(String::trim).map(String::toUpperCase).toList()
                : fromTxt;
        this.pollLimitPerState = pollLimitPerState;
        this.newsLimitPerState = newsLimitPerState;
    }

    private List<String> loadStatesFromTxt() {
        try {
            var resource = new ClassPathResource("states.txt");
            byte[] bytes = resource.getInputStream().readAllBytes();
            String text = new String(bytes, StandardCharsets.UTF_8);

            Set<String> abbrs = new LinkedHashSet<>();
            for (String line : text.split("\\R")) {
                String raw = line.trim();
                if (raw.isBlank()) {
                    continue;
                }
                // states.txt may contain entries like "Florida (special)".
                String normalizedName = raw.replaceAll("\\(.*\\)", "").trim();
                String abbr = STATE_ABBR_BY_NAME.get(normalizedName.toUpperCase(Locale.ROOT));
                if (abbr != null) {
                    abbrs.add(abbr);
                }
            }
            return List.copyOf(abbrs);
        } catch (IOException ex) {
            log.warn("Failed to load states.txt; falling back to app.states. ex={}", ex.getMessage());
            return List.of();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartupIngest() {
        refresh();
    }

    public synchronized String refresh() {
        try {
            List<Race> races = buildLiveRaces();
            races.forEach(repository::upsert);
            invalidateCache();
            boolean anyLive = races.stream().anyMatch(r ->
                    "LIVE_SENATE_CSV".equalsIgnoreCase(r.sourceType()) ||
                            "LIVE_538".equalsIgnoreCase(r.sourceType()));
            log.info("Using mixed polling data (curated+538+fallback). anyLive={}", anyLive);
            return anyLive ? "LIVE" : "FALLBACK";
        } catch (Exception ex) {
            log.warn("Live ingestion failed, switching to fallback: {}", ex.getMessage());
            List<Race> fallback = loadFallbackRaces();
            fallback.forEach(repository::upsert);
            invalidateCache();
            log.info("Using FALLBACK data");
            return "FALLBACK";
        }
    }

    private void invalidateCache() {
        cacheService.evictByPattern("states:*");
        cacheService.evictByPattern("state:*");
        cacheService.evictByPattern("race:*");
    }

    private List<Race> buildLiveRaces() throws IOException {
        Map<String, List<PollEntry>> curatedPollsByState = loadCuratedPollingData();

        // Pre-fetch 538 polls for states not covered by data.json.
        Map<String, List<PollEntry>> fiveThirtyEightPollsByState = Map.of();
        try {
            String csvPayload = restClient.get().uri(fiveThirtyEightPollUrl).retrieve().body(String.class);
            fiveThirtyEightPollsByState = parseFiveThirtyEightPolls(csvPayload);
        } catch (Exception ex) {
            log.warn("538 poll fetch failed (will use fallback/placeholder): {}", ex.getMessage());
            fiveThirtyEightPollsByState = Map.of();
        }

        // Static fallback polling data (currently incomplete, but used if both curated and 538 are missing).
        Map<String, Race> fallbackByState = loadFallbackRaces()
                .stream()
                .collect(Collectors.toMap(Race::state, r -> r));

        // Used to preserve previously ingested news when NewsAPI rate-limits us.
        Map<String, Race> existingByState = repository.findAll()
                .stream()
                .collect(Collectors.toMap(Race::state, r -> r, (a, b) -> a));

        Map<String, MarketOdds> oddsByState;
        try {
            oddsByState = polymarketClient.fetchOddsByState();
        } catch (Exception ex) {
            log.warn("Polymarket odds fetch failed: {}", ex.getMessage());
            oddsByState = Map.of();
        }
        if (oddsByState == null) {
            oddsByState = Map.of();
        }
        Map<String, MarketOdds> kalshiByState;
        try {
            kalshiByState = kalshiClient.fetchOddsByState();
        } catch (Exception ex) {
            log.warn("Kalshi odds fetch failed: {}", ex.getMessage());
            kalshiByState = Map.of();
        }
        if (kalshiByState == null) {
            kalshiByState = Map.of();
        }

        List<Race> races = new ArrayList<>();
        for (String state : states) {
            // Demand-driven news fetching happens in the `includeNews=true` path on `/state/{state}`.
            // During ingestion we avoid NewsAPI calls and instead keep any previously stored news,
            // then fallback to static fallback news if available.
            Race existingRace = existingByState.get(state);
            List<NewsArticle> news = (existingRace != null && existingRace.news() != null) ? existingRace.news() : List.of();
            if (news.isEmpty()) {
                Race fallbackRace = fallbackByState.get(state);
                news = (fallbackRace != null && fallbackRace.news() != null) ? fallbackRace.news() : List.of();
            }

            // 1) Curated polling (senate.csv) - preferred.
            List<PollEntry> curatedForState = curatedPollsByState.getOrDefault(state, List.of());
            if (!curatedForState.isEmpty()) {
                List<PollEntry> statePolls = choosePollsForState(state, curatedForState);
                Projection projection = projectionService.buildProjection(statePolls);
                boolean hasGeneralPolls = statePolls.stream()
                        .anyMatch(p -> "GENERAL".equalsIgnoreCase(p.raceStage()));
                races.add(new Race(
                        state + "-SEN",
                        state,
                        "SENATE",
                        statePolls,
                        projection,
                        projectionService.leadingParty(projection),
                        projectionService.margin(projection),
                        projectionService.color(projection),
                        news,
                        oddsByState.get(state),
                        kalshiByState.get(state),
                        List.of(),
                        Instant.now(),
                        hasGeneralPolls ? "LIVE_SENATE_CSV" : "LIVE_PRIMARY_ONLY"
                ));
                continue;
            }

            // 2) 538 polling (fallback polling results when curated is missing).
            List<PollEntry> csvForState = fiveThirtyEightPollsByState.getOrDefault(state, List.of());
            if (!csvForState.isEmpty()) {
                List<PollEntry> statePolls = choosePollsForState(state, csvForState);
                if (!statePolls.isEmpty()) {
                    Projection projection = projectionService.buildProjection(statePolls);
                    races.add(new Race(
                            state + "-SEN",
                            state,
                            "SENATE",
                            statePolls,
                            projection,
                            projectionService.leadingParty(projection),
                            projectionService.margin(projection),
                            projectionService.color(projection),
                            news,
                            oddsByState.get(state),
                            kalshiByState.get(state),
                            List.of(),
                            Instant.now(),
                            "LIVE_538"
                    ));
                    continue;
                }
            }

            // 3) No polling data available: keep state neutral/grey and avoid made-up polls.
            List<PollEntry> noPolls = List.of();
            Projection projection = projectionService.buildProjection(noPolls);
            races.add(new Race(
                    state + "-SEN",
                    state,
                    "SENATE",
                    noPolls,
                    projection,
                    projectionService.leadingParty(projection),
                    projectionService.margin(projection),
                    projectionService.color(projection),
                    news,
                    oddsByState.get(state),
                    kalshiByState.get(state),
                    List.of(),
                    Instant.now(),
                    "NO_POLL_DATA"
            ));
        }
        return races;
    }

    private List<NewsArticle> fallbackNewsForState(
            String state,
            Map<String, Race> existingByState,
            Map<String, Race> fallbackByState
    ) {
        Race existing = existingByState.get(state);
        if (existing != null && existing.news() != null && !existing.news().isEmpty()) {
            return existing.news();
        }
        Race fallbackRace = fallbackByState.get(state);
        if (fallbackRace != null && fallbackRace.news() != null && !fallbackRace.news().isEmpty()) {
            return fallbackRace.news();
        }
        // Ensure the UI doesn't show an empty-state for every state when NewsAPI is rate-limited.
        return List.of(new NewsArticle(
                "News temporarily unavailable (rate limited)",
                "https://example.com/news/" + state,
                "System"
        ));
    }

    private Map<String, Race> fallbackByStateCached() {
        if (fallbackByStateCache != null) {
            return fallbackByStateCache;
        }
        fallbackByStateCache = loadFallbackRaces()
                .stream()
                .collect(Collectors.toMap(Race::state, r -> r));
        return fallbackByStateCache;
    }

    /**
     * Demand-driven news fetching:
     * - Never calls NewsAPI during ingestion (to avoid 429).
     * - Only fetches if `race.news` is missing/empty.
     * - Caches fetched results in Redis to avoid repeated calls.
     */
    public Race ensureNewsLoaded(Race race) {
        if (race == null) {
            return null;
        }

        List<NewsArticle> current = race.news();
        if (current != null && !current.isEmpty()
                && !isPlaceholderOnlyNews(current)
                && !isIrrelevantNewsForState(race.state(), current)) {
            return race;
        }

        String state = race.state().toUpperCase(Locale.ROOT);
        String newsCacheKey = "news:state:" + state;
        String rateLimitedKey = "news:rateLimited:" + state;

        // 1) Use cached news if we have it.
        NewsArticle[] cached = cacheService.get(newsCacheKey, NewsArticle[].class);
        if (cached != null && cached.length > 0) {
            List<NewsArticle> cachedList = Arrays.asList(cached);
            if (!isPlaceholderOnlyNews(cachedList) && !isIrrelevantNewsForState(state, cachedList)) {
                return upsertRaceWithNews(race, cachedList);
            }
            cacheService.evictByPattern(newsCacheKey);
        }

        // 2) If we've recently been rate-limited, avoid re-fetching.
        String rateLimitedMarker = cacheService.get(rateLimitedKey, String.class);
        if (rateLimitedMarker != null) {
            return upsertRaceWithNews(race, fallbackNewsForState(state));
        }

        // 3) Try live fetch, then fallback.
        try {
            List<NewsArticle> fetched = fetchNews(state);
            if (fetched != null && !fetched.isEmpty()) {
                cacheService.put(newsCacheKey, fetched, NEWS_CACHE_TTL);
                cacheService.evictByPattern("state:" + state);
                return upsertRaceWithNews(race, fetched);
            }
            // No results -> fallback if present.
            return upsertRaceWithNews(race, fallbackNewsForState(state));
        } catch (NewsRateLimitException ex) {
            // Remember rate-limited state for a short time to avoid stampedes.
            cacheService.put(rateLimitedKey, "true", NEWS_RATE_LIMIT_TTL);
            return upsertRaceWithNews(race, fallbackNewsForState(state));
        }
    }

    private Race upsertRaceWithNews(Race race, List<NewsArticle> news) {
        if (news == null) {
            news = List.of();
        }
        Race updated = new Race(
                race.raceId(),
                race.state(),
                race.officeType(),
                race.polls(),
                race.projection(),
                race.leadingParty(),
                race.margin(),
                race.color(),
                news,
                race.odds(),
                race.kalshiOdds(),
                race.spending(),
                Instant.now(),
                race.sourceType()
        );
        repository.upsert(updated);
        cacheService.evictByPattern("state:" + race.state().toUpperCase(Locale.ROOT));
        return updated;
    }

    private List<NewsArticle> fallbackNewsForState(String state) {
        Race fallbackRace = fallbackByStateCached().get(state);
        if (fallbackRace != null && fallbackRace.news() != null && !fallbackRace.news().isEmpty()) {
            return fallbackRace.news();
        }
        // Last resort so the UI doesn't show "No recent news" for every state.
        return List.of(new NewsArticle(
                "News temporarily unavailable",
                "https://example.com/news/" + state,
                "System"
        ));
    }

    public Race ensureSpendingLoaded(Race race) {
        if (race == null) {
            return null;
        }
        if (race.spending() != null && !race.spending().isEmpty()) {
            return race;
        }
        List<CandidateSpending> spending;
        try {
            spending = openFecClient.fetchStateSenateSpending(race.state());
        } catch (Exception ex) {
            log.warn("OpenFEC spending fetch failed for state {}: {}", race.state(), ex.getMessage());
            spending = List.of();
        }
        Race updated = new Race(
                race.raceId(),
                race.state(),
                race.officeType(),
                race.polls(),
                race.projection(),
                race.leadingParty(),
                race.margin(),
                race.color(),
                race.news(),
                race.odds(),
                race.kalshiOdds(),
                spending,
                Instant.now(),
                race.sourceType()
        );
        repository.upsert(updated);
        cacheService.evictByPattern("state:" + race.state().toUpperCase(Locale.ROOT));
        return updated;
    }

    private boolean isPlaceholderOnlyNews(List<NewsArticle> news) {
        if (news == null || news.isEmpty()) {
            return true;
        }
        for (NewsArticle article : news) {
            String source = article.source() == null ? "" : article.source().trim().toLowerCase(Locale.ROOT);
            String title = article.title() == null ? "" : article.title().trim().toLowerCase(Locale.ROOT);
            String url = article.url() == null ? "" : article.url().trim().toLowerCase(Locale.ROOT);
            boolean placeholder = source.equals("fallback")
                    || source.equals("system")
                    || title.contains("temporarily unavailable")
                    || url.contains("example.com/");
            if (!placeholder) {
                return false;
            }
        }
        return true;
    }

    private boolean isIrrelevantNewsForState(String stateAbbr, List<NewsArticle> news) {
        String state = stateAbbr == null ? "" : stateAbbr.trim().toUpperCase(Locale.ROOT);
        String stateName = STATE_NAMES.getOrDefault(state, state);
        String stateNameLower = stateName.toLowerCase(Locale.ROOT);
        String abbrLower = state.toLowerCase(Locale.ROOT);
        for (NewsArticle article : news) {
            String haystack = ((article.title() == null ? "" : article.title()) + " " +
                    (article.url() == null ? "" : article.url()) + " " +
                    (article.source() == null ? "" : article.source())).toLowerCase(Locale.ROOT);
            if (haystack.contains(stateNameLower)
                    || haystack.contains("/" + abbrLower + "/")
                    || haystack.contains(" " + abbrLower + " ")) {
                return false;
            }
        }
        return true;
    }

    private List<Race> withFallbackForMissingStates(List<Race> liveRaces) {
        Map<String, Race> merged = new LinkedHashMap<>();
        liveRaces.forEach(race -> merged.put(race.state(), race));
        if (merged.size() == states.size()) {
            return new ArrayList<>(merged.values());
        }
        for (Race fallbackRace : loadFallbackRaces()) {
            merged.putIfAbsent(fallbackRace.state(), fallbackRace);
        }
        return new ArrayList<>(merged.values());
    }

    private Map<String, List<PollEntry>> parseFiveThirtyEightPolls(String csvPayload) {
        if (csvPayload == null || csvPayload.isBlank()) {
            return Map.of();
        }
        List<Map<String, String>> rows = readCsvRows(csvPayload);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Set<String> columns = rows.get(0).keySet();

        if (columns.containsAll(SENATE_CSV_COLUMNS)) {
            return parseSenateAggregateRows(rows);
        }
        return parseCandidateRows(rows);
    }

    private List<PollEntry> choosePollsForState(String state, List<PollEntry> fiveThirtyEightPolls) {
        List<PollEntry> deduped = dedupePolls(fiveThirtyEightPolls);
        boolean curated = deduped.stream().anyMatch(p -> CURATED_POLL_SOURCE.equalsIgnoreCase(p.source()));
        if (curated) {
            List<PollEntry> general = deduped.stream()
                    .filter(p -> "GENERAL".equalsIgnoreCase(p.raceStage()))
                    .sorted(Comparator.comparing(this::resolvedPollDate, Comparator.reverseOrder()))
                    .limit(pollLimitPerState)
                    .toList();
            List<PollEntry> primary = deduped.stream()
                    .filter(p -> p.raceStage() != null && p.raceStage().toUpperCase(Locale.ROOT).startsWith("PRIMARY"))
                    .sorted(Comparator.comparing(this::resolvedPollDate, Comparator.reverseOrder()))
                    .limit(pollLimitPerState)
                    .toList();
            if (!general.isEmpty()) {
                List<PollEntry> merged = new ArrayList<>(general);
                merged.addAll(primary);
                return merged;
            }
            return primary;
        }
        List<PollEntry> recentFiveThirtyEight = filterRecentPolls(deduped);
        if (!recentFiveThirtyEight.isEmpty()) {
            return recentFiveThirtyEight.stream()
                    .sorted(Comparator.comparing(this::resolvedPollDate, Comparator.reverseOrder()))
                    .limit(pollLimitPerState)
                    .toList();
        }

        // If no recent polls exist, use latest available 538 polls instead of falling back entirely.
        List<PollEntry> latestAvailable = deduped.stream()
                .sorted(Comparator.comparing(this::resolvedPollDate, Comparator.reverseOrder()))
                .limit(pollLimitPerState)
                .toList();
        if (!latestAvailable.isEmpty()) {
            log.warn("State {} has no polls in last {} days; using latest available 538 polls", state, recencyDays);
        }
        return latestAvailable;
    }

    private List<PollEntry> filterRecentPolls(List<PollEntry> polls) {
        LocalDate cutoff = LocalDate.now().minusDays(recencyDays);
        return polls.stream()
                .filter(p -> {
                    LocalDate date = parseDate(p.date()).orElse(parseDate(p.startDate()).orElse(null));
                    return date != null && !date.isBefore(cutoff);
                })
                .toList();
    }

    private List<PollEntry> dedupePolls(List<PollEntry> polls) {
        Set<String> seen = new LinkedHashSet<>();
        List<PollEntry> unique = new ArrayList<>();
        for (PollEntry poll : polls) {
            String key = (poll.pollster() + "|" + poll.date() + "|" + poll.party() + "|" + poll.raceStage() + "|" + poll.candidate())
                    .toUpperCase(Locale.ROOT);
            if (seen.add(key)) {
                unique.add(poll);
            }
        }
        return unique;
    }

    private Optional<String> latestPollDate(List<PollEntry> polls) {
        return polls.stream()
                .map(this::resolvedPollDate)
                .filter(d -> d != null)
                .max(LocalDate::compareTo)
                .map(LocalDate::toString);
    }

    private LocalDate resolvedPollDate(PollEntry poll) {
        return parseDate(poll.date())
                .orElse(parseDate(poll.startDate()).orElse(LocalDate.MIN));
    }

    private List<Map<String, String>> readCsvRows(String csvPayload) {
        try {
            CsvMapper mapper = new CsvMapper();
            mapper.enable(CsvParser.Feature.TRIM_SPACES);
            CsvSchema schema = CsvSchema.emptySchema().withHeader();
            return mapper.readerFor(new TypeReference<Map<String, String>>() {
                    })
                    .with(schema)
                    .<Map<String, String>>readValues(csvPayload)
                    .readAll()
                    .stream()
                    .map(this::normalizeRowKeys)
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse polling CSV", ex);
        }
    }

    private Map<String, String> normalizeRowKeys(Map<String, String> row) {
        Map<String, String> normalized = new HashMap<>();
        row.forEach((k, v) -> normalized.put(
                k == null ? "" : k.trim().toLowerCase(Locale.ROOT),
                v == null ? "" : v.trim()
        ));
        return normalized;
    }

    private Map<String, List<PollEntry>> parseSenateAggregateRows(List<Map<String, String>> rows) {
        Map<String, List<PollEntry>> pollsByState = new HashMap<>();

        Map<String, List<Map<String, String>>> rowsByState = rows.stream()
                .filter(row -> states.contains(row.getOrDefault("state", "").toUpperCase(Locale.ROOT)))
                .collect(Collectors.groupingBy(row -> row.getOrDefault("state", "").toUpperCase(Locale.ROOT)));

        // For this PoC we only care about the next Senate cycle specified by `data.json` (2026).
        // If the feed has no 2026 polls for a state, we intentionally keep it empty and let
        // the caller use placeholder/fallback polling.
        int targetYear = 2026;

        for (Map.Entry<String, List<Map<String, String>>> entry : rowsByState.entrySet()) {
            String state = entry.getKey();
            List<Map<String, String>> stateRows = entry.getValue();
            for (Map<String, String> row : stateRows) {
                if (extractRaceYear(row) != targetYear) {
                    continue;
                }
                String startDate = row.getOrDefault("start_date", "");
                double dem = parsePct(row.getOrDefault("dem_poll", ""));
                double rep = parsePct(row.getOrDefault("rep_poll", ""));
                List<PollEntry> statePolls = pollsByState.computeIfAbsent(state, key -> new ArrayList<>());
                statePolls.add(new PollEntry("FiveThirtyEight-AugustDataset", startDate, startDate, "538", "GENERAL", "Dem Candidate", "DEM", dem));
                statePolls.add(new PollEntry("FiveThirtyEight-AugustDataset", startDate, startDate, "538", "GENERAL", "Rep Candidate", "REP", rep));
            }
        }

        return pollsByState.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .sorted(Comparator.comparing(this::resolvedPollDate, Comparator.reverseOrder()))
                                .limit(pollLimitPerState)
                                .toList()
                ));
    }

    private Map<String, List<PollEntry>> parseCandidateRows(List<Map<String, String>> rows) {
        Map<String, List<Map<String, String>>> rowsByState = rows.stream()
                .filter(row -> "senate".equalsIgnoreCase(row.getOrDefault("office_type", "")))
                .filter(row -> states.contains(row.getOrDefault("state", "").toUpperCase(Locale.ROOT)))
                .filter(row -> {
                    String party = row.getOrDefault("party", "").toUpperCase(Locale.ROOT);
                    return "DEM".equals(party) || "REP".equals(party);
                })
                .collect(Collectors.groupingBy(row -> row.getOrDefault("state", "").toUpperCase(Locale.ROOT)));

        return rowsByState.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            int targetYear = 2026;
                            return entry.getValue().stream()
                                .filter(r -> extractRaceYear(r) == targetYear)
                                .sorted(Comparator.comparing(r -> r.getOrDefault("start_date", ""), Comparator.reverseOrder()))
                                .limit(pollLimitPerState)
                                .map(r -> new PollEntry(
                                        r.getOrDefault("pollster", ""),
                                        r.getOrDefault("start_date", ""),
                                        r.getOrDefault("start_date", ""),
                                        "538",
                                        "GENERAL",
                                        r.getOrDefault("candidate_name", ""),
                                        r.getOrDefault("party", "").toUpperCase(Locale.ROOT),
                                        parsePct(r.getOrDefault("pct", ""))
                                ))
                                .toList();
                        }
                ));
    }

    private double parsePct(String pctValue) {
        try {
            return Double.parseDouble(pctValue);
        } catch (Exception ex) {
            return 0.0;
        }
    }

    private List<NewsArticle> fetchNews(String state) {
        try {
            String stateName = STATE_NAMES.getOrDefault(state, state);
            return googleNewsClient.fetchStateNews(stateName, newsLimitPerState);
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.contains("429") || msg.toLowerCase(Locale.ROOT).contains("ratelimited")) {
                throw new NewsRateLimitException(msg);
            }
            log.warn("News fetch failed for state {}: {}", state, ex.getMessage());
            return List.of();
        }
    }

    private int extractRaceYear(Map<String, String> row) {
        String cycle = row.getOrDefault("cycle", "");
        if (!cycle.isBlank()) {
            try {
                return Integer.parseInt(cycle);
            } catch (NumberFormatException ignored) {
            }
        }
        String endDate = row.getOrDefault("end_date", "");
        if (endDate.length() >= 4) {
            try {
                return Integer.parseInt(endDate.substring(0, 4));
            } catch (NumberFormatException ignored) {
            }
        }
        String startDate = row.getOrDefault("start_date", "");
        if (startDate.length() >= 4) {
            try {
                return Integer.parseInt(startDate.substring(0, 4));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private Optional<LocalDate> parseDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return Optional.empty();
        }
        String token = rawDate.trim();
        // Handle ISO date ranges like "2025-11-06/2026-01-25" without
        // breaking normal US date formats such as "04/06/2026".
        if (token.contains("/") && token.contains("-")) {
            String[] rangeParts = token.split("/");
            token = rangeParts[rangeParts.length - 1].trim();
        }
        // Handle ISO dates directly (e.g. "2026-04-07") before any splitting.
        // Some feeds also include additional text after the date; we only take the first 10 chars.
        if (token.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            token = token.substring(0, 10);
        } else if (token.contains("-")) {
            token = token.split("-")[0].trim();
        }
        List<DateTimeFormatter> formats = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("M/d/yy"),
                DateTimeFormatter.ofPattern("M/d/yyyy"),
                DateTimeFormatter.ofPattern("MM/dd/yy"),
                DateTimeFormatter.ofPattern("MM/dd/yyyy")
        );
        for (DateTimeFormatter formatter : formats) {
            try {
                return Optional.of(LocalDate.parse(token, formatter.withLocale(Locale.US)));
            } catch (DateTimeParseException ignored) {
            }
        }
        return Optional.empty();
    }

    /**
     * Loads curated polling data from {@code backend/src/main/resources/senate.csv} and converts it into
     * {@link PollEntry} items understood by the rest of the pipeline.
     */
    private Map<String, List<PollEntry>> loadCuratedPollingData() {
        try {
            var resource = new ClassPathResource("senate.csv");
            String csvPayload = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            List<Map<String, String>> rows = readCsvRows(csvPayload);
            if (rows.isEmpty()) {
                return Map.of();
            }

            Map<String, List<PollEntry>> pollsByState = new HashMap<>();

            // Aggregate general rows so each poll contributes at most one DEM and one REP value.
            Map<String, PollEntry> generalDemByKey = new HashMap<>();
            Map<String, PollEntry> generalRepByKey = new HashMap<>();

            for (Map<String, String> row : rows) {
                String officeType = row.getOrDefault("office_type", "");
                if (!officeType.toLowerCase(Locale.ROOT).contains("senate")) {
                    continue;
                }
                if (extractRaceYear(row) != 2026) {
                    continue;
                }
                String state = row.getOrDefault("state", "").toUpperCase(Locale.ROOT);
                if (!states.contains(state)) {
                    continue;
                }

                String stage = row.getOrDefault("stage", "").toLowerCase(Locale.ROOT);
                String party = row.getOrDefault("party", "").toUpperCase(Locale.ROOT);
                String candidate = row.getOrDefault("candidate_name", row.getOrDefault("answer", "")).trim();
                if (!isUsableCandidate(candidate)) {
                    continue;
                }

                String pollster = row.getOrDefault("pollster", "");
                String rawEndDate = row.getOrDefault("end_date", "").trim();
                String rawStartDate = row.getOrDefault("start_date", "").trim();
                String selectedDate = !rawEndDate.isBlank() ? rawEndDate : rawStartDate;
                String date = normalizePollDate(selectedDate, null);
                double pct = parsePct(row.getOrDefault("pct", ""));
                if (pct <= 0.0) {
                    continue;
                }

                if ("general".equals(stage) && ("DEM".equals(party) || "REP".equals(party))) {
                    String key = String.join("|",
                            state,
                            row.getOrDefault("poll_id", ""),
                            row.getOrDefault("question_id", ""),
                            pollster,
                            date
                    ).toUpperCase(Locale.ROOT);
                    PollEntry next = new PollEntry(pollster, date, date, CURATED_POLL_SOURCE, "GENERAL", candidate, party, pct);
                    if ("DEM".equals(party)) {
                        PollEntry existing = generalDemByKey.get(key);
                        if (existing == null || next.pct() > existing.pct()) {
                            generalDemByKey.put(key, next);
                        }
                    } else {
                        PollEntry existing = generalRepByKey.get(key);
                        if (existing == null || next.pct() > existing.pct()) {
                            generalRepByKey.put(key, next);
                        }
                    }
                    continue;
                }

                if (stage.startsWith("primary") && ("DEM".equals(party) || "REP".equals(party))) {
                    String raceStage = "DEM".equals(party) ? "PRIMARY_DEM" : "PRIMARY_REP";
                    pollsByState.computeIfAbsent(state, ignored -> new ArrayList<>())
                            .add(new PollEntry(pollster, date, date, CURATED_POLL_SOURCE, raceStage, candidate, party, pct));
                }
            }

            for (Map.Entry<String, PollEntry> entry : generalDemByKey.entrySet()) {
                String key = entry.getKey();
                PollEntry dem = entry.getValue();
                PollEntry rep = generalRepByKey.get(key);
                if (rep == null) {
                    continue;
                }
                String state = key.split("\\|", 2)[0];
                pollsByState.computeIfAbsent(state, ignored -> new ArrayList<>()).add(dem);
                pollsByState.get(state).add(rep);
            }

            return pollsByState;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load curated polling senate.csv", ex);
        }
    }

    private boolean isUsableCandidate(String candidate) {
        if (candidate == null) {
            return false;
        }
        String normalized = candidate.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        return !normalized.contains("don't know")
                && !normalized.contains("dont know")
                && !normalized.contains("would not vote")
                && !normalized.contains("someone else")
                && !normalized.equals("other")
                && !normalized.contains("undecided");
    }

    private void collectPrimaryPollEntries(
            JsonNode primaryElection,
            String fieldName,
            String party,
            String raceStage,
            List<PollEntry> out
    ) {
        JsonNode polls = primaryElection.path(fieldName);
        if (!polls.isArray()) {
            return;
        }
        for (JsonNode pollNode : polls) {
            String pollster = pollNode.path("pollster").asText("");
            String date = normalizePollDate(pollNode.path("date").asText("N/A"), null);
            JsonNode results = pollNode.path("results");
            if (!results.isObject()) {
                continue;
            }
            for (var field : iterable(results.fields())) {
                String candidate = field.getKey();
                JsonNode value = field.getValue();
                if (candidate == null || "other".equalsIgnoreCase(candidate.trim()) || value == null || value.isNull()) {
                    continue;
                }
                out.add(new PollEntry(
                        pollster,
                        date,
                        date,
                        "NEW_DATA_JSON",
                        raceStage,
                        candidate.trim(),
                        party,
                        value.asDouble(0.0)
                ));
            }
        }
    }

    private String mostLikelyPrimaryCandidateSlug(JsonNode partyPrimaryPolls) {
        if (!partyPrimaryPolls.isArray()) {
            return null;
        }
        Map<String, double[]> stats = new HashMap<>();
        for (JsonNode pollNode : partyPrimaryPolls) {
            JsonNode results = pollNode.path("results");
            if (!results.isObject()) {
                continue;
            }
            for (var field : iterable(results.fields())) {
                String candidate = field.getKey();
                JsonNode value = field.getValue();
                if (candidate == null || value == null || value.isNull() || "other".equalsIgnoreCase(candidate.trim())) {
                    continue;
                }
                double[] agg = stats.computeIfAbsent(slugify(candidate), k -> new double[2]);
                agg[0] += value.asDouble(0.0);
                agg[1] += 1.0;
            }
        }
        return stats.entrySet().stream()
                .max(Comparator.comparingDouble(e -> e.getValue()[0] / e.getValue()[1]))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private Set<String> collectPrimaryCandidateSlugs(JsonNode partyPrimaryPolls) {
        if (!partyPrimaryPolls.isArray()) {
            return Set.of();
        }
        Set<String> slugs = new LinkedHashSet<>();
        for (JsonNode pollNode : partyPrimaryPolls) {
            JsonNode results = pollNode.path("results");
            if (!results.isObject()) {
                continue;
            }
            for (var field : iterable(results.fields())) {
                String candidate = field.getKey();
                JsonNode value = field.getValue();
                if (candidate == null || value == null || value.isNull() || "other".equalsIgnoreCase(candidate.trim())) {
                    continue;
                }
                slugs.add(slugify(candidate));
            }
        }
        return slugs;
    }

    private GeneralMatchupSelection selectGeneralMatchup(JsonNode generalElection, String preferredDemSlug, String preferredRepSlug) {
        if (!generalElection.isObject()) {
            return null;
        }
        if (preferredDemSlug != null && preferredRepSlug != null) {
            String target = "matchup_" + preferredDemSlug + "_vs_" + preferredRepSlug;
            JsonNode preferred = generalElection.path(target);
            if (preferred.isArray() && !preferred.isEmpty()) {
                return new GeneralMatchupSelection(target, preferred);
            }
        }
        String bestField = null;
        JsonNode bestPolls = null;
        int bestCount = -1;
        for (var field : iterable(generalElection.fields())) {
            JsonNode polls = field.getValue();
            if (!field.getKey().startsWith("matchup_") || !polls.isArray() || polls.isEmpty()) {
                continue;
            }
            if (polls.size() > bestCount) {
                bestField = field.getKey();
                bestPolls = polls;
                bestCount = polls.size();
            }
        }
        return bestField == null ? null : new GeneralMatchupSelection(bestField, bestPolls);
    }

    private String[] extractMatchupCandidates(
            GeneralMatchupSelection selectedMatchup,
            String preferredDemSlug,
            String preferredRepSlug,
            Set<String> knownDemSlugs,
            Set<String> knownRepSlugs
    ) {
        if (selectedMatchup == null || selectedMatchup.fieldName() == null || !selectedMatchup.fieldName().startsWith("matchup_")) {
            return new String[]{null, null};
        }
        String body = selectedMatchup.fieldName().substring("matchup_".length());
        String[] parts = body.split("_vs_", 2);
        if (parts.length == 2) {
            String first = parts[0].trim().toLowerCase(Locale.ROOT);
            String second = parts[1].trim().toLowerCase(Locale.ROOT);
            if (preferredDemSlug != null) {
                if (preferredDemSlug.equals(first)) {
                    return new String[]{first, second};
                }
                if (preferredDemSlug.equals(second)) {
                    return new String[]{second, first};
                }
            }
            if (preferredRepSlug != null) {
                if (preferredRepSlug.equals(first)) {
                    return new String[]{second, first};
                }
                if (preferredRepSlug.equals(second)) {
                    return new String[]{first, second};
                }
            }
            if (knownDemSlugs.contains(first) && !knownDemSlugs.contains(second)) {
                return new String[]{first, second};
            }
            if (knownDemSlugs.contains(second) && !knownDemSlugs.contains(first)) {
                return new String[]{second, first};
            }
            if (knownRepSlugs.contains(first) && !knownRepSlugs.contains(second)) {
                return new String[]{second, first};
            }
            if (knownRepSlugs.contains(second) && !knownRepSlugs.contains(first)) {
                return new String[]{first, second};
            }
        }
        JsonNode polls = selectedMatchup.polls();
        if (!polls.isArray() || polls.isEmpty()) {
            return new String[]{null, null};
        }
        JsonNode firstResults = polls.get(0).path("results");
        if (!firstResults.isObject()) {
            return new String[]{null, null};
        }
        List<String> candidates = new ArrayList<>();
        for (var field : iterable(firstResults.fields())) {
            String key = field.getKey();
            if (key != null && !"other".equalsIgnoreCase(key.trim()) && !field.getValue().isNull()) {
                candidates.add(key);
            }
        }
        if (candidates.size() < 2) {
            return new String[]{null, null};
        }
        return new String[]{slugify(candidates.get(0)), slugify(candidates.get(1))};
    }

    private void collectGeneralPollEntries(JsonNode matchupPolls, String demSlug, String repSlug, List<PollEntry> out) {
        LocalDate latestValidDate = null;
        for (JsonNode pollNode : matchupPolls) {
            String rawDate = pollNode.path("date").asText("");
            if (rawDate == null || rawDate.isBlank() || "N/A".equalsIgnoreCase(rawDate.trim())) {
                continue;
            }
            Optional<LocalDate> parsed = parseDate(rawDate);
            if (parsed.isPresent() && (latestValidDate == null || parsed.get().isAfter(latestValidDate))) {
                latestValidDate = parsed.get();
            }
        }

        for (JsonNode pollNode : matchupPolls) {
            JsonNode results = pollNode.path("results");
            if (!results.isObject()) {
                continue;
            }
            String demCandidate = findCandidateKey(results, demSlug);
            String repCandidate = findCandidateKey(results, repSlug);
            if (demCandidate == null || repCandidate == null) {
                continue;
            }
            JsonNode demNode = results.get(demCandidate);
            JsonNode repNode = results.get(repCandidate);
            if (demNode == null || repNode == null || demNode.isNull() || repNode.isNull()) {
                continue;
            }

            String pollster = pollNode.path("pollster").asText("");
            String date = normalizePollDate(pollNode.path("date").asText("N/A"), latestValidDate);
            out.add(new PollEntry(pollster, date, date, "NEW_DATA_JSON", "GENERAL", demCandidate, "DEM", demNode.asDouble(0.0)));
            out.add(new PollEntry(pollster, date, date, "NEW_DATA_JSON", "GENERAL", repCandidate, "REP", repNode.asDouble(0.0)));
        }
    }

    private String normalizePollDate(String rawDate, LocalDate fallbackDate) {
        String dateForEntry = rawDate == null ? "N/A" : rawDate.trim();
        Optional<LocalDate> parsed = parseDate(dateForEntry);
        if (parsed.isPresent()) {
            return parsed.get().toString();
        }
        if (fallbackDate != null) {
            return fallbackDate.toString();
        }
        return "N/A";
    }

    private String resolveStateAbbr(String stateName) {
        if (stateName == null || stateName.isBlank()) {
            return null;
        }
        String normalizedName = stateName.replaceAll("\\(.*\\)", "").trim();
        for (var e : STATE_NAMES.entrySet()) {
            if (e.getValue().equalsIgnoreCase(normalizedName)) {
                return e.getKey();
            }
        }
        return null;
    }

    private String findCandidateKey(JsonNode resultsNode, String candidateSlug) {
        if (resultsNode == null || resultsNode.isNull() || candidateSlug == null) {
            return null;
        }
        for (var it = resultsNode.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (key != null && slugify(key).equals(candidateSlug)) {
                return key;
            }
        }
        return null;
    }

    private String slugify(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /**
     * Small helper to avoid verbose iterator->iterable conversions in the JSON parsing logic.
     */
    private static <T> Iterable<T> iterable(java.util.Iterator<T> iterator) {
        return () -> iterator;
    }

    private List<Race> loadFallbackRaces() {
        try {
            var resource = new ClassPathResource("fallback/races.json");
            byte[] bytes = resource.getInputStream().readAllBytes();
            List<Race> races = objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8), new TypeReference<>() {
            });
            return races.stream().map(r -> new Race(
                    r.raceId(),
                    r.state(),
                    r.officeType(),
                    r.polls(),
                    r.projection(),
                    r.leadingParty(),
                    r.margin(),
                    r.color(),
                    r.news(),
                    r.odds(),
                    r.kalshiOdds(),
                    List.of(),
                    Instant.now(),
                    "FALLBACK"
            )).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Fallback data is missing or invalid", e);
        }
    }
}

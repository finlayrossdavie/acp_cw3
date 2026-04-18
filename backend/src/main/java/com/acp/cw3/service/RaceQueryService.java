package com.acp.cw3.service;

import com.acp.cw3.dto.StateSummaryDto;
import com.acp.cw3.model.Race;
import com.acp.cw3.repository.DynamoRaceRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
public class RaceQueryService {
    private final DynamoRaceRepository repository;
    private final RedisCacheService cacheService;
    private static final Duration TTL = Duration.ofSeconds(300);

    public RaceQueryService(DynamoRaceRepository repository, RedisCacheService cacheService) {
        this.repository = repository;
        this.cacheService = cacheService;
    }

    public List<StateSummaryDto> getStates() {
        String key = "states:summary";
        StateSummaryDto[] cached = cacheService.get(key, StateSummaryDto[].class);
        if (cached != null) {
            return Arrays.stream(cached).toList();
        }
        List<StateSummaryDto> value = repository.findAll().stream()
                .map(r -> new StateSummaryDto(r.state(), r.leadingParty(), r.margin(), r.color(), r.updatedAt()))
                .sorted((a, b) -> a.state().compareToIgnoreCase(b.state()))
                .toList();
        cacheService.put(key, value, TTL);
        return value;
    }

    public Optional<Race> getByState(String state) {
        String key = "state:" + state.toUpperCase();
        Race cached = cacheService.get(key, Race.class);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Race> value = repository.findByState(state.toUpperCase());
        value.ifPresent(r -> cacheService.put(key, r, TTL));
        return value;
    }

    public Optional<Race> getById(String id) {
        String key = "race:" + id;
        Race cached = cacheService.get(key, Race.class);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Race> value = repository.findByRaceId(id);
        value.ifPresent(r -> cacheService.put(key, r, TTL));
        return value;
    }
}

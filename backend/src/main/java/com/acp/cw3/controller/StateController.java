package com.acp.cw3.controller;

import com.acp.cw3.dto.StateSummaryDto;
import com.acp.cw3.model.Race;
import com.acp.cw3.service.IngestionService;
import com.acp.cw3.service.RaceQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping
public class StateController {
    private final RaceQueryService raceQueryService;
    private final IngestionService ingestionService;

    public StateController(RaceQueryService raceQueryService, IngestionService ingestionService) {
        this.raceQueryService = raceQueryService;
        this.ingestionService = ingestionService;
    }

    @GetMapping("/states")
    public List<StateSummaryDto> getStates() {
        return raceQueryService.getStates();
    }

    @GetMapping("/state/{state}")
    public ResponseEntity<Race> getState(
            @PathVariable String state,
            @RequestParam(name = "includeNews", defaultValue = "true") boolean includeNews,
            @RequestParam(name = "includeSpending", defaultValue = "true") boolean includeSpending
    ) {
        return raceQueryService.getByState(state)
                .map(race -> {
                    Race withNews = includeNews ? ingestionService.ensureNewsLoaded(race) : race;
                    Race withSpending = includeSpending ? ingestionService.ensureSpendingLoaded(withNews) : withNews;
                    if (!includeNews || !includeSpending) {
                        return ResponseEntity.ok(new Race(
                                withSpending.raceId(),
                                withSpending.state(),
                                withSpending.officeType(),
                                withSpending.polls(),
                                withSpending.projection(),
                                withSpending.leadingParty(),
                                withSpending.margin(),
                                withSpending.color(),
                                includeNews ? withSpending.news() : List.of(),
                                withSpending.odds(),
                                withSpending.kalshiOdds(),
                                includeSpending ? withSpending.spending() : List.of(),
                                withSpending.updatedAt(),
                                withSpending.sourceType()
                        ));
                    }
                    return ResponseEntity.ok(withSpending);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

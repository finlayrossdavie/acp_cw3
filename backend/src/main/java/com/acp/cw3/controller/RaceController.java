package com.acp.cw3.controller;

import com.acp.cw3.model.Race;
import com.acp.cw3.service.RaceQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class RaceController {
    private final RaceQueryService raceQueryService;

    public RaceController(RaceQueryService raceQueryService) {
        this.raceQueryService = raceQueryService;
    }

    @GetMapping("/race/{id}")
    public ResponseEntity<Race> getRace(@PathVariable String id) {
        return raceQueryService.getById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

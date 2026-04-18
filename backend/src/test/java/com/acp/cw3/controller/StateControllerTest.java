package com.acp.cw3.controller;

import com.acp.cw3.dto.StateSummaryDto;
import com.acp.cw3.model.Race;
import com.acp.cw3.service.IngestionService;
import com.acp.cw3.service.RaceQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StateController.class)
class StateControllerTest {
    @Autowired
    MockMvc mockMvc;

    @MockBean
    RaceQueryService raceQueryService;

    @MockBean
    IngestionService ingestionService;

    @Test
    void returnsStatesSummary() throws Exception {
        Mockito.when(raceQueryService.getStates()).thenReturn(List.of(
                new StateSummaryDto("NC", "DEM", 2.0, "grey", Instant.now())
        ));

        mockMvc.perform(get("/states"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].state").value("NC"));
    }

    @Test
    void returnsNotFoundForUnknownState() throws Exception {
        Mockito.when(raceQueryService.getByState("ZZ")).thenReturn(Optional.empty());
        mockMvc.perform(get("/state/ZZ"))
                .andExpect(status().isNotFound());
    }
}

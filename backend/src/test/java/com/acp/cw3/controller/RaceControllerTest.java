package com.acp.cw3.controller;

import com.acp.cw3.service.RaceQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RaceController.class)
class RaceControllerTest {
    @Autowired
    MockMvc mockMvc;

    @MockBean
    RaceQueryService raceQueryService;

    @Test
    void returnsNotFoundForUnknownRace() throws Exception {
        Mockito.when(raceQueryService.getById("missing")).thenReturn(Optional.empty());
        mockMvc.perform(get("/race/missing"))
                .andExpect(status().isNotFound());
    }
}

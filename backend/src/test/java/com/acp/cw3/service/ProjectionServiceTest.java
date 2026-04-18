package com.acp.cw3.service;

import com.acp.cw3.model.PollEntry;
import com.acp.cw3.model.Projection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectionServiceTest {
    private final ProjectionService service = new ProjectionService(3.0);

    @Test
    void buildsProjectionAndColorForCloseRace() {
        List<PollEntry> polls = List.of(
                new PollEntry("A", "2026-01-01", "2026-01-01", "538", "GENERAL", "Dem", "DEM", 48.0),
                new PollEntry("B", "2026-01-01", "2026-01-01", "538", "GENERAL", "Rep", "REP", 46.5)
        );

        Projection projection = service.buildProjection(polls);
        assertEquals(48.0, projection.demAvg());
        assertEquals(46.5, projection.repAvg());
        assertEquals("DEM", service.leadingParty(projection));
        assertEquals("grey", service.color(projection));
    }

    @Test
    void weightsMoreRecentPollsHigher() {
        List<PollEntry> polls = List.of(
                new PollEntry("OldDEM", "2025-01-01", "2025-01-01", "538", "GENERAL", "Dem", "DEM", 40.0),
                new PollEntry("NewDEM", "2026-04-10", "2026-04-10", "538", "GENERAL", "Dem", "DEM", 50.0),
                new PollEntry("OldREP", "2025-01-01", "2025-01-01", "538", "GENERAL", "Rep", "REP", 50.0),
                new PollEntry("NewREP", "2026-04-10", "2026-04-10", "538", "GENERAL", "Rep", "REP", 45.0)
        );

        Projection projection = service.buildProjection(polls);
        assertEquals("DEM", service.leadingParty(projection));
    }
}

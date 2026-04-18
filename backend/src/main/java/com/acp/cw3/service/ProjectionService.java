package com.acp.cw3.service;

import com.acp.cw3.model.PollEntry;
import com.acp.cw3.model.Projection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class ProjectionService {
    private final double closeRaceThreshold;

    public ProjectionService(@Value("${app.close-race-threshold}") double closeRaceThreshold) {
        this.closeRaceThreshold = closeRaceThreshold;
    }

    public Projection buildProjection(List<PollEntry> polls) {
        double demAvg = weightedAverageForParty(polls, "DEM");
        double repAvg = weightedAverageForParty(polls, "REP");
        return new Projection(demAvg, repAvg);
    }

    private double weightedAverageForParty(List<PollEntry> polls, String party) {
        LocalDate now = LocalDate.now();
        double weightedSum = 0.0;
        double weightTotal = 0.0;
        for (PollEntry poll : polls) {
            if (!"GENERAL".equalsIgnoreCase(poll.raceStage())) {
                continue;
            }
            if (!party.equalsIgnoreCase(poll.party())) {
                continue;
            }
            LocalDate pollDate = parseDateSafe(poll.date());
            long days = pollDate == null ? 365 : Math.max(0, ChronoUnit.DAYS.between(pollDate, now));
            double weight = 1.0 / (days + 1.0);
            weightedSum += poll.pct() * weight;
            weightTotal += weight;
        }
        if (weightTotal == 0.0) {
            return 0.0;
        }
        return weightedSum / weightTotal;
    }

    private LocalDate parseDateSafe(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(rawDate);
        } catch (Exception ex) {
            return null;
        }
    }

    public double margin(Projection projection) {
        return Math.abs(projection.demAvg() - projection.repAvg());
    }

    public String leadingParty(Projection projection) {
        return projection.demAvg() > projection.repAvg() ? "DEM" : "REP";
    }

    public String color(Projection projection) {
        if (margin(projection) < closeRaceThreshold) {
            return "grey";
        }
        return leadingParty(projection).equals("DEM") ? "blue" : "red";
    }
}

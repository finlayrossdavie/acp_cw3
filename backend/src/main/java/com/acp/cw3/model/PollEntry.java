package com.acp.cw3.model;

public record PollEntry(
        String pollster,
        String startDate,
        String date,
        String source,
        String raceStage,
        String candidate,
        String party,
        double pct
) {
    public PollEntry(String pollster, String startDate, String candidate, String party, double pct) {
        this(pollster, startDate, startDate, "UNKNOWN", "GENERAL", candidate, party, pct);
    }
}

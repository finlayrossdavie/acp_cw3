import { BettingOdds, CandidateSpending, NewsArticle, Poll, PrimaryPollSnapshot, StateData } from "@/types/election";
import { CURRENT_SEAT_PARTY_BY_STATE } from "@/data/currentSeatParties";

type Cw3StateSummary = {
  state: string;
  leadingParty: string;
  margin: number;
  color: string;
  updatedAt: string;
};

type Cw3PollEntry = {
  pollster: string;
  startDate: string;
  date: string;
  source: string;
  raceStage: string;
  candidate: string;
  party: string;
  pct: number;
};

type Cw3Race = {
  raceId: string;
  state: string;
  officeType: string;
  polls: Cw3PollEntry[];
  projection: { demAvg: number; repAvg: number };
  leadingParty: string;
  margin: number;
  color: string;
  news: Array<{ title: string; url: string; source: string }>;
  odds: null | {
    source: string;
    demProbability: number;
    repProbability: number;
    lastUpdated: string;
    republicanPrimary: Array<{ candidate: string; party: string; probability: number }>;
    democraticPrimary: Array<{ candidate: string; party: string; probability: number }>;
  };
  kalshiOdds?: null | {
    source: string;
    demProbability: number;
    repProbability: number;
    lastUpdated: string;
    republicanPrimary?: Array<{ candidate: string; party: string; probability: number }>;
    democraticPrimary?: Array<{ candidate: string; party: string; probability: number }>;
  };
  spending?: Array<{
    candidateId: string;
    candidateName: string;
    party: string;
    committeeDisbursements: number;
    independentSupport: number;
    totalFor: number;
  }>;
  updatedAt: string;
  sourceType: string;
};

const STATE_NAMES: Record<string, string> = {
  AL: "Alabama",
  AK: "Alaska",
  AZ: "Arizona",
  AR: "Arkansas",
  CA: "California",
  CO: "Colorado",
  CT: "Connecticut",
  DE: "Delaware",
  FL: "Florida",
  GA: "Georgia",
  HI: "Hawaii",
  ID: "Idaho",
  IL: "Illinois",
  IN: "Indiana",
  IA: "Iowa",
  KS: "Kansas",
  KY: "Kentucky",
  LA: "Louisiana",
  ME: "Maine",
  MD: "Maryland",
  MA: "Massachusetts",
  MI: "Michigan",
  MN: "Minnesota",
  MS: "Mississippi",
  MO: "Missouri",
  MT: "Montana",
  NE: "Nebraska",
  NV: "Nevada",
  NH: "New Hampshire",
  NJ: "New Jersey",
  NM: "New Mexico",
  NY: "New York",
  NC: "North Carolina",
  ND: "North Dakota",
  OH: "Ohio",
  OK: "Oklahoma",
  OR: "Oregon",
  PA: "Pennsylvania",
  RI: "Rhode Island",
  SC: "South Carolina",
  SD: "South Dakota",
  TN: "Tennessee",
  TX: "Texas",
  UT: "Utah",
  VT: "Vermont",
  VA: "Virginia",
  WA: "Washington",
  WV: "West Virginia",
  WI: "Wisconsin",
  WY: "Wyoming",
  DC: "District of Columbia",
};

export function adaptCw3SummariesToMap(
  summaries: Cw3StateSummary[],
  baselineStates: StateData[]
): StateData[] {
  const summaryByState = new Map(summaries.map((s) => [s.state.toUpperCase(), s]));
  return baselineStates.map((base) => {
    const summary = summaryByState.get(base.abbr);
    if (!summary) {
      return {
        ...base,
        margin: 0,
        demPercent: 50,
        repPercent: 50,
        hasGeneralPolling: false,
        covered: false,
      };
    }
    const signedMargin = summary.leadingParty?.toUpperCase() === "DEM" ? -summary.margin : summary.margin;
    const demPercent = clamp(50 - signedMargin / 2);
    const repPercent = clamp(50 + signedMargin / 2);
    return {
      ...base,
      name: STATE_NAMES[base.abbr] || base.name,
      margin: signedMargin,
      demPercent,
      repPercent,
      hasGeneralPolling: true,
      covered: true,
    };
  });
}

export function adaptCw3PollsToUi(polls: Cw3PollEntry[]): Poll[] {
  const grouped = new Map<string, { pollster: string; date: string; dem?: number; rep?: number }>();
  const generalPolls = (polls || []).filter((entry) => (entry.raceStage || "").toUpperCase() === "GENERAL");
  for (const entry of generalPolls) {
    const key = `${entry.pollster}|${entry.date || entry.startDate}`;
    const bucket = grouped.get(key) || {
      pollster: entry.pollster || "Unknown",
      date: entry.date || entry.startDate || "",
    };
    if (entry.party?.toUpperCase() === "DEM") {
      bucket.dem = entry.pct;
    } else if (entry.party?.toUpperCase() === "REP") {
      bucket.rep = entry.pct;
    }
    grouped.set(key, bucket);
  }

  return Array.from(grouped.values())
    .map((item) => {
      const demPercent = item.dem ?? 0;
      const repPercent = item.rep ?? 0;
      return {
        pollster: item.pollster,
        date: item.date || "Unknown date",
        demPercent,
        repPercent,
        margin: repPercent - demPercent,
        sampleSize: 0,
        raceStage: "GENERAL",
      };
    })
    .sort((a, b) => b.date.localeCompare(a.date));
}

export function adaptCw3PrimaryPollsToUi(polls: Cw3PollEntry[]): PrimaryPollSnapshot[] {
  const grouped = new Map<string, PrimaryPollSnapshot>();
  for (const entry of polls || []) {
    const stage = (entry.raceStage || "").toUpperCase();
    if (stage === "GENERAL" || !stage.startsWith("PRIMARY")) {
      continue;
    }
    const party: "DEM" | "REP" | "OTHER" =
      stage === "PRIMARY_DEM" ? "DEM" : stage === "PRIMARY_REP" ? "REP" : "OTHER";
    const key = `${party}|${entry.pollster || "Unknown"}|${entry.date || entry.startDate || ""}`;
    const existing = grouped.get(key) || {
      pollster: entry.pollster || "Unknown",
      date: entry.date || entry.startDate || "Unknown date",
      party,
      candidates: [],
    };
    existing.candidates.push({ candidate: entry.candidate || "Unknown", pct: entry.pct ?? 0 });
    grouped.set(key, existing);
  }
  return Array.from(grouped.values())
    .map((snapshot) => ({
      ...snapshot,
      candidates: [...snapshot.candidates].sort((a, b) => b.pct - a.pct),
    }))
    .sort((a, b) => b.date.localeCompare(a.date));
}

export function adaptCw3OddsToUi(odds: Cw3Race["odds"], kalshiOdds?: Cw3Race["kalshiOdds"]): BettingOdds[] {
  const out: BettingOdds[] = [];
  if (odds) {
    out.push({
      source: odds.source || "POLYMARKET",
      demOdds: Math.round(clamp(odds.demProbability) * 100),
      repOdds: Math.round(clamp(odds.repProbability) * 100),
      lastUpdated: odds.lastUpdated ? new Date(odds.lastUpdated).toLocaleString() : "Unknown",
      republicanPrimaryCandidates: (odds.republicanPrimary || [])
        .filter((c) => (c.probability ?? 0) > 0.05)
        .map((c) => ({ candidate: c.candidate, odds: Math.round(clamp(c.probability) * 100) })),
      democraticPrimaryCandidates: (odds.democraticPrimary || [])
        .filter((c) => (c.probability ?? 0) > 0.05)
        .map((c) => ({ candidate: c.candidate, odds: Math.round(clamp(c.probability) * 100) })),
    });
  }
  if (kalshiOdds) {
    out.push({
      source: kalshiOdds.source || "KALSHI",
      demOdds: Math.round(clamp(kalshiOdds.demProbability) * 100),
      repOdds: Math.round(clamp(kalshiOdds.repProbability) * 100),
      lastUpdated: kalshiOdds.lastUpdated ? new Date(kalshiOdds.lastUpdated).toLocaleString() : "Unknown",
      republicanPrimaryCandidates: [],
      democraticPrimaryCandidates: [],
    });
  }
  return out;
}

export function adaptCw3NewsToUi(news: Cw3Race["news"]): NewsArticle[] {
  return (news || []).map((article, idx) => ({
    id: `${idx}-${article.url || article.title}`,
    title: article.title,
    source: article.source || "Unknown",
    date: "Latest",
    snippet: article.title,
    url: article.url,
  }));
}

export function adaptCw3SpendingToUi(spending: Cw3Race["spending"]): CandidateSpending[] {
  return (spending || []).map((entry) => ({
    candidateId: entry.candidateId,
    candidateName: entry.candidateName,
    party:
      entry.party?.toUpperCase() === "DEM"
        ? "DEM"
        : entry.party?.toUpperCase() === "REP"
          ? "REP"
          : "OTHER",
    committeeDisbursements: Number(entry.committeeDisbursements || 0),
    independentSupport: Number(entry.independentSupport || 0),
    totalFor: Number(entry.totalFor || 0),
  }));
}

export function adaptCw3RaceToPartialMapState(race: Cw3Race): StateData {
  const hasGeneralPolls = (race.polls || []).some((p) => (p.raceStage || "").toUpperCase() === "GENERAL");
  const signedMargin = hasGeneralPolls
    ? (race.leadingParty?.toUpperCase() === "DEM" ? -race.margin : race.margin)
    : 0;

  const marketDemPercent = race.odds ? clamp(race.odds.demProbability * 100) : undefined;
  const marketRepPercent = race.odds ? clamp(race.odds.repProbability * 100) : undefined;
  // Scale market probability difference onto the same approximate -30..+30 range as polling margin.
  const marketMargin =
    race.odds && marketDemPercent != null && marketRepPercent != null
      ? (marketRepPercent - marketDemPercent) * 0.3
      : undefined;

  const spendingTotal = (race.spending || []).reduce((sum, entry) => sum + Number(entry.totalFor || 0), 0);

  return {
    abbr: race.state.toUpperCase(),
    name: STATE_NAMES[race.state.toUpperCase()] || race.state.toUpperCase(),
    margin: signedMargin,
    demPercent: hasGeneralPolls ? clamp(race.projection?.demAvg ?? 50) : 50,
    repPercent: hasGeneralPolls ? clamp(race.projection?.repAvg ?? 50) : 50,
    hasGeneralPolling: hasGeneralPolls,
    marketDemPercent,
    marketRepPercent,
    marketMargin,
    spendingTotal,
    covered: true,
  };
}

export function applyCurrentSeatParties(states: StateData[]): StateData[] {
  return states.map((state) => {
    const party = CURRENT_SEAT_PARTY_BY_STATE[state.abbr];
    if (!party) {
      return state;
    }
    return {
      ...state,
      incumbentParty: party,
      incumbentMargin: party === "DEM" ? -30 : 30,
    };
  });
}

function clamp(value: number): number {
  return Math.max(0, Math.min(100, value));
}


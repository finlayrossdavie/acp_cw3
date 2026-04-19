export type RaceType = 'senate' | 'house';

export interface StateData {
  abbr: string;
  name: string;
  margin: number; // negative = Dem lead, positive = Rep lead
  electoralVotes?: number;
  demPercent: number;
  repPercent: number;
  covered?: boolean;
  hasGeneralPolling?: boolean;
  // Optional: prediction-market-based values (derived from CW3 `Race.odds`)
  marketDemPercent?: number;
  marketRepPercent?: number;
  // Scaled to roughly match polling margin (-30..+30) for consistent colour mapping
  marketMargin?: number;
  incumbentParty?: 'DEM' | 'REP';
  // Fixed strong margin for confirmed held seat color mode.
  incumbentMargin?: number;
  spendingTotal?: number;
}

export interface Poll {
  pollster: string;
  date: string;
  demPercent: number;
  repPercent: number;
  margin: number;
  sampleSize: number;
  raceStage?: string;
}

export interface PrimaryPollSnapshot {
  pollster: string;
  date: string;
  party: 'DEM' | 'REP' | 'OTHER';
  candidates: Array<{ candidate: string; pct: number }>;
}

export interface BettingOdds {
  source: string;
  demOdds: number;
  repOdds: number;
  lastUpdated: string;
  republicanPrimaryCandidates?: Array<{ candidate: string; odds: number }>;
  democraticPrimaryCandidates?: Array<{ candidate: string; odds: number }>;
}

export interface NewsArticle {
  id: string;
  title: string;
  source: string;
  date: string;
  snippet: string;
  url: string;
}

export interface CandidateSpending {
  candidateId: string;
  candidateName: string;
  party: 'DEM' | 'REP' | 'OTHER';
  committeeDisbursements: number;
  independentSupport: number;
  totalFor: number;
}

export interface StateDetail {
  state: StateData;
  polls: Poll[];
  bettingOdds: BettingOdds[];
  news: NewsArticle[];
  spending?: CandidateSpending[];
}

export interface StateRaceInfo {
  primaryDate: string;
  electionDate: string;
  blurb: string;
}

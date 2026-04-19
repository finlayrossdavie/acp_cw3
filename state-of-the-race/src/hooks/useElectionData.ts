import { useQuery } from '@tanstack/react-query';
import { RaceType, StateData, Poll, BettingOdds, NewsArticle, PrimaryPollSnapshot, CandidateSpending } from '@/types/election';
import { getStatesData, getStatePolls, getStateBettingOdds, getStateNews } from '@/data/mockData';
import {
  adaptCw3NewsToUi,
  adaptCw3OddsToUi,
  adaptCw3PollsToUi,
  adaptCw3PrimaryPollsToUi,
  adaptCw3SpendingToUi,
  adaptCw3RaceToPartialMapState,
  adaptCw3SummariesToMap,
  applyCurrentSeatParties,
} from '@/lib/cw3Adapter';

// Dev: default to local backend. Prod: VITE_API_BASE_URL must be set at build time (HTTPS API URL, e.g. api.<domain>).
// If prod uses localhost or fetch fails (CORS / mixed content), we must not silently show mockData.
const API_BASE =
  import.meta.env.VITE_API_BASE_URL || (import.meta.env.DEV ? 'http://localhost:8080' : '');

const isProd = import.meta.env.PROD;

async function fetchWithFallback<T>(url: string, fallback: () => T): Promise<T> {
  if (!API_BASE) return fallback();
  try {
    const res = await fetch(`${API_BASE}${url}`);
    if (!res.ok) throw new Error('API error');
    return res.json();
  } catch {
    return fallback();
  }
}

async function fetchStateDetail(stateAbbr: string, includeNews: boolean, includeSpending: boolean) {
  if (!API_BASE || !stateAbbr) return null;
  try {
    const res = await fetch(`${API_BASE}/state/${stateAbbr}?includeNews=${includeNews}&includeSpending=${includeSpending}`);
    if (!res.ok) throw new Error('API error');
    return res.json();
  } catch {
    return null;
  }
}

async function buildMapData(race: RaceType, includeSpending: boolean): Promise<StateData[]> {
  const baseline = getStatesData('senate');
  const fallbackCovered = baseline.map((state) => ({ ...state, covered: true }));
  if (!API_BASE || race !== 'senate') {
    if (isProd && race === 'senate' && !API_BASE) {
      throw new Error(
        'Missing VITE_API_BASE_URL. Rebuild with HTTPS API URL (e.g. https://api.yourdomain.com).'
      );
    }
    return applyCurrentSeatParties(fallbackCovered);
  }
  const summaries = await fetchWithFallback(`/states`, () => [] as any[]);
  if (!Array.isArray(summaries) || summaries.length === 0) {
    if (isProd) {
      throw new Error(
        'Could not load /states from the API (empty or unreachable). Check HTTPS URL, CORS, and DynamoDB data. Rebuild after fixing VITE_API_BASE_URL.'
      );
    }
    return applyCurrentSeatParties(fallbackCovered);
  }
  let mapped = adaptCw3SummariesToMap(summaries as any[], baseline);

  const covered = mapped.filter((s) => s.covered).map((s) => s.abbr);
  const details = await Promise.all(
    covered.map((abbr) => fetchStateDetail(abbr, false, includeSpending))
  );
  const detailMap = new Map(
    details.filter(Boolean).map((detail: any) => [detail.state?.toUpperCase(), adaptCw3RaceToPartialMapState(detail)])
  );
  mapped = mapped.map((state) => detailMap.get(state.abbr) ? { ...state, ...detailMap.get(state.abbr)! } : state);
  return applyCurrentSeatParties(mapped);
}

export function useMapData(race: RaceType) {
  return useQuery<StateData[]>({
    queryKey: ['map', race],
    queryFn: async () => buildMapData(race, false),
    staleTime: 60_000,
  });
}

export function useSpendingMapData(race: RaceType, enabled: boolean) {
  return useQuery<StateData[]>({
    queryKey: ['map-spending', race],
    queryFn: async () => buildMapData(race, true),
    staleTime: 60_000,
    enabled,
  });
}

export function useStatePolls(race: RaceType, stateAbbr: string) {
  return useQuery<Poll[]>({
    queryKey: ['polls', race, stateAbbr],
    queryFn: async () => {
      if (API_BASE && race === 'senate') {
        const detail = await fetchStateDetail(stateAbbr, false, false);
        if (detail) {
          return adaptCw3PollsToUi(detail.polls || []);
        }
        return isProd ? [] : getStatePolls(stateAbbr);
      }
      return isProd ? [] : getStatePolls(stateAbbr);
    },
    enabled: !!stateAbbr,
  });
}

export function useStatePrimaryPolls(race: RaceType, stateAbbr: string) {
  return useQuery<PrimaryPollSnapshot[]>({
    queryKey: ['primary-polls', race, stateAbbr],
    queryFn: async () => {
      if (API_BASE && race === 'senate') {
        const detail = await fetchStateDetail(stateAbbr, false, false);
        if (detail) {
          return adaptCw3PrimaryPollsToUi(detail.polls || []);
        }
      }
      return [];
    },
    enabled: !!stateAbbr,
  });
}

export function useStateBettingOdds(race: RaceType, stateAbbr: string) {
  return useQuery<BettingOdds[]>({
    queryKey: ['odds', race, stateAbbr],
    queryFn: async () => {
      if (API_BASE && race === 'senate') {
        const detail = await fetchStateDetail(stateAbbr, false, false);
        if (detail) {
          return adaptCw3OddsToUi(detail.odds || null, detail.kalshiOdds || null);
        }
        return isProd ? [] : getStateBettingOdds(stateAbbr);
      }
      return isProd ? [] : getStateBettingOdds(stateAbbr);
    },
    enabled: !!stateAbbr,
  });
}

export function useStateNews(stateAbbr: string) {
  return useQuery<NewsArticle[]>({
    queryKey: ['news', stateAbbr],
    queryFn: async () => {
      if (API_BASE) {
        const detail = await fetchStateDetail(stateAbbr, true, false);
        if (detail) {
          return adaptCw3NewsToUi(detail.news || []);
        }
        return isProd ? [] : getStateNews(stateAbbr);
      }
      return isProd ? [] : getStateNews(stateAbbr);
    },
    enabled: !!stateAbbr,
  });
}

export function useStateSpending(race: RaceType, stateAbbr: string) {
  return useQuery<CandidateSpending[]>({
    queryKey: ['spending', race, stateAbbr],
    queryFn: async () => {
      if (API_BASE && race === 'senate') {
        const detail = await fetchStateDetail(stateAbbr, false, true);
        if (detail) {
          return adaptCw3SpendingToUi(detail.spending || []);
        }
      }
      return [];
    },
    enabled: !!stateAbbr,
  });
}

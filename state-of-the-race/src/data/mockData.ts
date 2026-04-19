import { StateData, Poll, BettingOdds, NewsArticle, RaceType } from '@/types/election';

// Margin: negative = Dem lead, positive = Rep lead
const senateMargins: Record<string, number> = {
  AL: 25, AK: 8, AZ: -2, AR: 22, CA: -30, CO: -12, CT: -18, DE: -20, FL: 5,
  GA: -1, HI: -35, ID: 30, IL: -20, IN: 15, IA: 6, KS: 12, KY: 20, LA: 18,
  ME: -8, MD: -25, MA: -30, MI: -3, MN: -6, MS: 15, MO: 10, MT: 4, NE: 14,
  NV: -1, NH: -5, NJ: -12, NM: -8, NY: -18, NC: 2, ND: 25, OH: 6, OK: 28,
  OR: -10, PA: -2, RI: -22, SC: 14, SD: 22, TN: 20, TX: 8, UT: 18, VT: -25,
  VA: -8, WA: -16, WV: 28, WI: -1, WY: 35, DC: -70,
};

const houseMargins: Record<string, number> = {
  AL: 20, AK: 6, AZ: -1, AR: 18, CA: -22, CO: -8, CT: -12, DE: -15, FL: 8,
  GA: 2, HI: -28, ID: 25, IL: -14, IN: 12, IA: 4, KS: 10, KY: 16, LA: 14,
  ME: -6, MD: -20, MA: -25, MI: -2, MN: -4, MS: 12, MO: 8, MT: 6, NE: 10,
  NV: 1, NH: -3, NJ: -8, NM: -6, NY: -12, NC: 3, ND: 20, OH: 8, OK: 24,
  OR: -7, PA: -1, RI: -18, SC: 12, SD: 18, TN: 16, TX: 10, UT: 15, VT: -20,
  VA: -5, WA: -12, WV: 24, WI: 0, WY: 30, DC: -65,
};

const stateNames: Record<string, string> = {
  AL: 'Alabama', AK: 'Alaska', AZ: 'Arizona', AR: 'Arkansas', CA: 'California',
  CO: 'Colorado', CT: 'Connecticut', DE: 'Delaware', FL: 'Florida', GA: 'Georgia',
  HI: 'Hawaii', ID: 'Idaho', IL: 'Illinois', IN: 'Indiana', IA: 'Iowa',
  KS: 'Kansas', KY: 'Kentucky', LA: 'Louisiana', ME: 'Maine', MD: 'Maryland',
  MA: 'Massachusetts', MI: 'Michigan', MN: 'Minnesota', MS: 'Mississippi',
  MO: 'Missouri', MT: 'Montana', NE: 'Nebraska', NV: 'Nevada', NH: 'New Hampshire',
  NJ: 'New Jersey', NM: 'New Mexico', NY: 'New York', NC: 'North Carolina',
  ND: 'North Dakota', OH: 'Ohio', OK: 'Oklahoma', OR: 'Oregon', PA: 'Pennsylvania',
  RI: 'Rhode Island', SC: 'South Carolina', SD: 'South Dakota', TN: 'Tennessee',
  TX: 'Texas', UT: 'Utah', VT: 'Vermont', VA: 'Virginia', WA: 'Washington',
  WV: 'West Virginia', WI: 'Wisconsin', WY: 'Wyoming', DC: 'District of Columbia',
};

const electoralVotes: Record<string, number> = {
  AL: 9, AK: 3, AZ: 11, AR: 6, CA: 54, CO: 10, CT: 7, DE: 3, FL: 30,
  GA: 16, HI: 4, ID: 4, IL: 19, IN: 11, IA: 6, KS: 6, KY: 8, LA: 8,
  ME: 4, MD: 10, MA: 11, MI: 15, MN: 10, MS: 6, MO: 10, MT: 4, NE: 5,
  NV: 6, NH: 4, NJ: 14, NM: 5, NY: 28, NC: 16, ND: 3, OH: 17, OK: 7,
  OR: 8, PA: 19, RI: 4, SC: 9, SD: 3, TN: 11, TX: 40, UT: 6, VT: 3,
  VA: 13, WA: 12, WV: 4, WI: 10, WY: 3, DC: 3,
};

export function getStatesData(race: RaceType): StateData[] {
  const margins = race === 'senate' ? senateMargins : houseMargins;
  return Object.entries(margins).map(([abbr, margin]) => ({
    abbr,
    name: stateNames[abbr] || abbr,
    margin,
    electoralVotes: electoralVotes[abbr],
    demPercent: 50 - margin / 2,
    repPercent: 50 + margin / 2,
  }));
}

export function getStatePolls(stateAbbr: string): Poll[] {
  const base = senateMargins[stateAbbr] || 0;
  return [
    { pollster: 'Reuters/Ipsos', date: '2024-10-28', demPercent: 50 - base/2 + 1, repPercent: 50 + base/2 - 1, margin: base - 2, sampleSize: 1200 },
    { pollster: 'Quinnipiac', date: '2024-10-25', demPercent: 50 - base/2 - 0.5, repPercent: 50 + base/2 + 0.5, margin: base + 1, sampleSize: 950 },
    { pollster: 'Fox News', date: '2024-10-22', demPercent: 50 - base/2 + 2, repPercent: 50 + base/2 - 2, margin: base - 4, sampleSize: 1100 },
    { pollster: 'CNN/SSRS', date: '2024-10-20', demPercent: 50 - base/2, repPercent: 50 + base/2, margin: base, sampleSize: 1050 },
    { pollster: 'Emerson College', date: '2024-10-18', demPercent: 50 - base/2 + 0.5, repPercent: 50 + base/2 - 0.5, margin: base - 1, sampleSize: 880 },
  ];
}

export function getStateBettingOdds(stateAbbr: string): BettingOdds[] {
  const margin = senateMargins[stateAbbr] || 0;
  const demBase = Math.max(5, Math.min(95, 50 - margin));
  return [
    { source: 'PredictIt', demOdds: demBase + 2, repOdds: 100 - demBase - 2, lastUpdated: '2024-10-29' },
    { source: 'Polymarket', demOdds: demBase - 1, repOdds: 100 - demBase + 1, lastUpdated: '2024-10-29' },
    { source: 'Kalshi', demOdds: demBase, repOdds: 100 - demBase, lastUpdated: '2024-10-28' },
  ];
}

export function getStateNews(stateAbbr: string): NewsArticle[] {
  const name = stateNames[stateAbbr] || stateAbbr;
  return [
    { id: '1', title: `${name} Senate Race Tightens in Final Days`, source: 'Associated Press', date: '2024-10-29', snippet: `New polling shows the ${name} race within the margin of error as both candidates make final campaign pushes.`, url: '#' },
    { id: '2', title: `Campaign Spending Surges in ${name}`, source: 'Reuters', date: '2024-10-28', snippet: `Outside groups have poured millions into ${name} ads, making it one of the most expensive races in the country.`, url: '#' },
    { id: '3', title: `Early Voting Numbers Break Records in ${name}`, source: 'NBC News', date: '2024-10-27', snippet: `${name} election officials report unprecedented early voter turnout, surpassing 2020 levels.`, url: '#' },
  ];
}

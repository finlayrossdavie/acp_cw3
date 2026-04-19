import { RaceType } from '@/types/election';
import { useStatePolls, useStatePrimaryPolls, useStateBettingOdds, useStateNews, useStateSpending } from '@/hooks/useElectionData';
import { formatMaxOneDecimal, getMarginLabel, getMarginColor } from '@/lib/colorUtils';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { ScrollArea } from '@/components/ui/scroll-area';
import { ArrowLeft, BarChart3, TrendingUp, Newspaper, Landmark } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { STATE_RACE_INFO_BY_ABBR } from '@/data/stateRaceInfo';
import { ChartContainer, ChartLegend, ChartLegendContent, ChartTooltip, ChartTooltipContent } from '@/components/ui/chart';
import { Cell, Pie, PieChart } from 'recharts';

interface StateDetailPanelProps {
  stateAbbr: string;
  stateName: string;
  margin: number;
  hasGeneralPolling?: boolean;
  incumbentParty?: 'DEM' | 'REP';
  race: RaceType;
  onBack: () => void;
}

export default function StateDetailPanel({
  stateAbbr,
  stateName,
  margin,
  hasGeneralPolling = true,
  incumbentParty,
  race,
  onBack,
}: StateDetailPanelProps) {
  const { data: polls } = useStatePolls(race, stateAbbr);
  const { data: primaryPolls } = useStatePrimaryPolls(race, stateAbbr);
  const { data: odds } = useStateBettingOdds(race, stateAbbr);
  const { data: spending } = useStateSpending(race, stateAbbr);
  const { data: news } = useStateNews(stateAbbr);
  const raceInfo = STATE_RACE_INFO_BY_ABBR[stateAbbr];
  const formattedPrimaryDate = raceInfo ? formatPrimaryDate(raceInfo.primaryDate) : null;
  const financeView = buildFinanceView(spending || []);
  const hasGeneral = hasGeneralPolling !== false;
  const inferredSafeMargin = !hasGeneral && incumbentParty
    ? (incumbPartyToMargin(incumbentParty))
    : margin;
  const inferredDemPercent = Math.max(0, Math.min(100, 50 - inferredSafeMargin / 2));
  const inferredRepPercent = Math.max(0, Math.min(100, 50 + inferredSafeMargin / 2));
  const headerLabel = hasGeneral
    ? getMarginLabel(margin)
    : incumbentParty
      ? `No general polling · Safe ${incumbentParty === 'DEM' ? 'Democrat' : 'Republican'}`
      : 'No general polling available';

  return (
    <div className="h-full flex flex-col">
      {/* Header */}
      <div className="p-4 border-b border-border">
        <Button variant="ghost" size="sm" onClick={onBack} className="mb-2 -ml-2 text-muted-foreground">
          <ArrowLeft className="w-4 h-4 mr-1" /> Back to map
        </Button>
        <div className="flex items-center gap-3">
          <div
            className="w-4 h-4 rounded-sm"
            style={{ backgroundColor: getMarginColor(margin) }}
          />
          <div>
            <h2 className="font-heading text-xl font-bold">{stateName}</h2>
            <p className="text-sm text-muted-foreground">
              {headerLabel}
            </p>
          </div>
        </div>

        {/* Quick bar */}
        <div className="mt-3 flex items-center gap-2 text-sm">
          <span className="text-dem font-semibold">D {formatMaxOneDecimal(inferredDemPercent)}%</span>
          <div className="flex-1 h-2.5 rounded-full bg-muted overflow-hidden flex">
            <div className="bg-dem h-full transition-all" style={{ width: `${inferredDemPercent}%` }} />
            <div className="bg-rep h-full transition-all" style={{ width: `${inferredRepPercent}%` }} />
          </div>
          <span className="text-rep font-semibold">R {formatMaxOneDecimal(inferredRepPercent)}%</span>
        </div>

        {raceInfo && (
          <Card className="mt-3 bg-card">
            <CardHeader className="pb-2">
              <CardTitle className="text-sm">Race Information</CardTitle>
            </CardHeader>
            <CardContent className="pt-0">
              <div className="flex flex-wrap gap-4 text-xs text-muted-foreground mb-2">
                <span><span className="font-medium text-foreground">Primary:</span> {formattedPrimaryDate}</span>
              </div>
              <p className="text-sm text-muted-foreground leading-relaxed">{raceInfo.blurb}</p>
            </CardContent>
          </Card>
        )}
      </div>

      {/* Tabs */}
      <Tabs defaultValue="polls" className="flex-1 flex flex-col min-h-0">
        <TabsList className="mx-4 mt-3 w-fit">
          <TabsTrigger value="polls" className="gap-1.5"><BarChart3 className="w-3.5 h-3.5" /> Polls</TabsTrigger>
          <TabsTrigger value="betting" className="gap-1.5"><TrendingUp className="w-3.5 h-3.5" /> Betting</TabsTrigger>
          <TabsTrigger value="finance" className="gap-1.5"><Landmark className="w-3.5 h-3.5" /> Finance</TabsTrigger>
          <TabsTrigger value="news" className="gap-1.5"><Newspaper className="w-3.5 h-3.5" /> News</TabsTrigger>
        </TabsList>

        <ScrollArea className="flex-1 min-h-0">
          <TabsContent value="polls" className="px-4 pb-4 mt-0">
            <div className="space-y-2 mt-3">
              {(polls && polls.length > 0) ? polls.map((poll, i) => (
                <Card key={i} className="bg-card">
                  <CardContent className="p-3">
                    <div className="flex justify-between items-start">
                      <div>
                        <p className="font-medium text-sm">{poll.pollster}</p>
                        <p className="text-xs text-muted-foreground">
                          {poll.date} · n={poll.sampleSize > 0 ? poll.sampleSize : 'N/A'}
                        </p>
                      </div>
                      <div className={`text-sm font-semibold ${poll.margin < 0 ? 'text-dem' : 'text-rep'}`}>
                        {poll.margin < 0 ? 'D' : 'R'}+{formatMaxOneDecimal(Math.abs(poll.margin))}
                      </div>
                    </div>
                    <div className="mt-2 flex items-center gap-2 text-xs">
                      <span className="text-dem">D {formatMaxOneDecimal(poll.demPercent)}%</span>
                      <div className="flex-1 h-1.5 rounded-full bg-muted overflow-hidden flex">
                        <div className="bg-dem h-full" style={{ width: `${poll.demPercent}%` }} />
                        <div className="bg-rep h-full" style={{ width: `${poll.repPercent}%` }} />
                      </div>
                      <span className="text-rep">R {formatMaxOneDecimal(poll.repPercent)}%</span>
                    </div>
                  </CardContent>
                </Card>
              )) : (
                <Card className="bg-card">
                  <CardContent className="p-3 text-sm text-muted-foreground">
                    No general election polling data available for this state.
                  </CardContent>
                </Card>
              )}

              {primaryPolls && primaryPolls.length > 0 && (
                <Card className="bg-card">
                  <CardHeader className="pb-2">
                    <CardTitle className="text-sm">Primary Polling</CardTitle>
                  </CardHeader>
                  <CardContent className="pt-0 space-y-3">
                    {primaryPolls.map((snapshot, idx) => (
                      <div key={`primary-${idx}`} className="border border-border rounded-md p-2">
                        <div className="flex justify-between items-center text-xs mb-1.5">
                          <span className="font-medium">{snapshot.pollster}</span>
                          <span className="text-muted-foreground">{snapshot.date}</span>
                        </div>
                        <p className="text-xs text-muted-foreground mb-1.5">
                          {snapshot.party === 'DEM' ? 'Democratic primary' : snapshot.party === 'REP' ? 'Republican primary' : 'All-party primary'}
                        </p>
                        <div className="space-y-1.5">
                          {snapshot.candidates.map((candidate, candidateIdx) => (
                            <div key={`primary-candidate-${idx}-${candidateIdx}`} className="text-xs">
                              <div className="flex justify-between">
                                <span className="truncate pr-2">{candidate.candidate}</span>
                                <span className="font-medium">{formatMaxOneDecimal(candidate.pct)}%</span>
                              </div>
                              <div className="h-1.5 rounded-full bg-muted mt-1 overflow-hidden">
                                <div
                                  className={`h-full ${
                                    snapshot.party === 'DEM' ? 'bg-dem' : snapshot.party === 'REP' ? 'bg-rep' : 'bg-muted-foreground'
                                  }`}
                                  style={{ width: `${Math.max(0, Math.min(100, candidate.pct))}%` }}
                                />
                              </div>
                            </div>
                          ))}
                        </div>
                      </div>
                    ))}
                  </CardContent>
                </Card>
              )}
            </div>
          </TabsContent>

          <TabsContent value="finance" className="px-4 pb-4 mt-0">
            <div className="space-y-2 mt-3">
              {financeView.candidates.length > 0 ? (
                <Card className="bg-card">
                  <CardHeader className="pb-2">
                    <CardTitle className="text-sm">Campaign Funding Summary</CardTitle>
                  </CardHeader>
                  <CardContent className="pt-0 space-y-3">
                    <div className="border border-border rounded-md p-2.5">
                      <p className="text-xs text-muted-foreground">Total candidate disbursements in {stateAbbr}</p>
                      <p className="text-lg font-semibold mt-0.5">{formatUsd(financeView.totalSpend)}</p>
                    </div>

                    <div className="grid grid-cols-3 gap-2">
                      {financeView.partyTotals.map((party) => (
                        <div key={party.party} className="border border-border rounded-md p-2 text-center">
                          <p className={`text-xs font-semibold ${party.colorClass}`}>{party.label}</p>
                          <p className="text-xs text-muted-foreground mt-1">{formatUsd(party.value)}</p>
                          <p className="text-[11px] text-muted-foreground">{formatMaxOneDecimal(party.share)}%</p>
                        </div>
                      ))}
                    </div>

                    <div className="border border-border rounded-md p-2.5">
                      <ChartContainer
                        config={{
                          dem: { label: 'Democrats', color: '#2563eb' },
                          rep: { label: 'Republicans', color: '#dc2626' },
                          ind: { label: 'Independents', color: '#6b7280' },
                        }}
                        className="mx-auto h-[220px] max-w-[320px]"
                      >
                        <PieChart>
                          <ChartTooltip
                            content={
                              <ChartTooltipContent
                                formatter={(_, __, item) => {
                                  const row = item.payload as { label: string; value: number; share: number };
                                  return (
                                    <div className="flex justify-between gap-4 w-full">
                                      <span>{row.label}</span>
                                      <span className="font-medium">{formatUsd(row.value)} ({formatMaxOneDecimal(row.share)}%)</span>
                                    </div>
                                  );
                                }}
                              />
                            }
                          />
                          <Pie data={financeView.partyChart} dataKey="value" nameKey="label" innerRadius={48} outerRadius={80} paddingAngle={2}>
                            {financeView.partyChart.map((slice) => (
                              <Cell key={slice.key} fill={slice.color} />
                            ))}
                          </Pie>
                          <ChartLegend content={<ChartLegendContent />} />
                        </PieChart>
                      </ChartContainer>
                    </div>

                    <div className="space-y-2">
                      <p className="text-xs font-semibold text-muted-foreground">Top candidate spenders</p>
                      {financeView.candidates.map((entry) => {
                        const partyClass = entry.party === 'DEM' ? 'text-dem' : entry.party === 'REP' ? 'text-rep' : 'text-foreground';
                        const barClass = entry.party === 'DEM' ? 'bg-dem' : entry.party === 'REP' ? 'bg-rep' : 'bg-muted-foreground';
                        const widthPct = Math.max(3, (entry.totalFor / financeView.maxCandidateSpend) * 100);
                        return (
                          <div key={entry.candidateId} className="border border-border rounded-md p-2.5">
                            <div className="flex justify-between gap-2 text-xs">
                              <p className={`font-semibold truncate ${partyClass}`}>
                                {entry.candidateName}
                              </p>
                              <p className={`font-medium ${partyClass}`}>{entry.party}</p>
                            </div>
                            <div className="mt-1.5 h-2 rounded-full bg-muted overflow-hidden">
                              <div className={`h-full ${barClass}`} style={{ width: `${widthPct}%` }} />
                            </div>
                            <div className="mt-1.5 text-xs text-muted-foreground flex justify-between gap-2">
                              <span>Disbursements: {formatUsd(entry.totalFor)}</span>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  </CardContent>
                </Card>
              ) : (
                <Card className="bg-card">
                  <CardContent className="p-3 text-sm text-muted-foreground">
                    Candidate spending data unavailable for this state.
                  </CardContent>
                </Card>
              )}
            </div>
          </TabsContent>

          <TabsContent value="betting" className="px-4 pb-4 mt-0">
            <div className="space-y-2 mt-3">
              {(odds && odds.length > 0) ? odds.map((odd, i) => (
                <Card key={i} className="bg-card">
                  <CardContent className="p-3">
                    <p className="font-medium text-sm mb-2">{odd.source}</p>
                    <div className="flex items-center gap-3">
                      <div className="flex-1">
                        <div className="flex justify-between text-xs mb-1">
                          <span className="text-dem font-medium">Dem {odd.demOdds}¢</span>
                          <span className="text-rep font-medium">Rep {odd.repOdds}¢</span>
                        </div>
                        <div className="h-3 rounded-full bg-muted overflow-hidden flex">
                          <div className="bg-dem h-full rounded-l-full transition-all" style={{ width: `${Math.max(0, Math.min(100, odd.demOdds))}%` }} />
                          <div className="bg-rep h-full rounded-r-full transition-all" style={{ width: `${Math.max(0, Math.min(100, odd.repOdds))}%` }} />
                        </div>
                      </div>
                    </div>
                    <p className="text-xs text-muted-foreground mt-1.5">Updated {odd.lastUpdated}</p>

                    {((odd.republicanPrimaryCandidates && odd.republicanPrimaryCandidates.length > 0) ||
                      (odd.democraticPrimaryCandidates && odd.democraticPrimaryCandidates.length > 0)) && (
                      <div className="mt-3 border-t border-border pt-3 space-y-3">
                        {odd.republicanPrimaryCandidates && odd.republicanPrimaryCandidates.length > 0 && (
                          <div>
                            <p className="text-xs font-semibold text-muted-foreground mb-1.5">Republican primary</p>
                            <div className="space-y-1.5">
                              {odd.republicanPrimaryCandidates.map((cand, idx) => (
                                <div key={`rep-${idx}`} className="text-xs">
                                  <div className="flex justify-between">
                                    <span className="truncate pr-2">{cand.candidate}</span>
                                    <span className="font-medium">{cand.odds}¢</span>
                                  </div>
                                  <div className="h-1.5 rounded-full bg-muted mt-1 overflow-hidden">
                                    <div className="h-full bg-rep" style={{ width: `${Math.max(0, Math.min(100, cand.odds))}%` }} />
                                  </div>
                                </div>
                              ))}
                            </div>
                          </div>
                        )}

                        {odd.democraticPrimaryCandidates && odd.democraticPrimaryCandidates.length > 0 && (
                          <div>
                            <p className="text-xs font-semibold text-muted-foreground mb-1.5">Democratic primary</p>
                            <div className="space-y-1.5">
                              {odd.democraticPrimaryCandidates.map((cand, idx) => (
                                <div key={`dem-${idx}`} className="text-xs">
                                  <div className="flex justify-between">
                                    <span className="truncate pr-2">{cand.candidate}</span>
                                    <span className="font-medium">{cand.odds}¢</span>
                                  </div>
                                  <div className="h-1.5 rounded-full bg-muted mt-1 overflow-hidden">
                                    <div className="h-full bg-dem" style={{ width: `${Math.max(0, Math.min(100, cand.odds))}%` }} />
                                  </div>
                                </div>
                              ))}
                            </div>
                          </div>
                        )}
                      </div>
                    )}
                  </CardContent>
                </Card>
              )) : (
                <Card className="bg-card">
                  <CardContent className="p-3 text-sm text-muted-foreground">
                    Prediction market odds unavailable.
                  </CardContent>
                </Card>
              )}
            </div>
          </TabsContent>

          <TabsContent value="news" className="px-4 pb-4 mt-0">
            <div className="space-y-2 mt-3">
              {(news && news.length > 0) ? news.map((article) => (
                <Card key={article.id} className="bg-card hover:bg-accent/50 transition-colors cursor-pointer">
                  <CardContent className="p-3">
                    <a className="font-medium text-sm leading-snug hover:underline" href={article.url} target="_blank" rel="noreferrer">
                      {article.title}
                    </a>
                    <p className="text-xs text-muted-foreground mt-1">{article.source} · {article.date}</p>
                    <p className="text-xs text-muted-foreground mt-1.5 line-clamp-2">{article.snippet}</p>
                  </CardContent>
                </Card>
              )) : (
                <Card className="bg-card">
                  <CardContent className="p-3 text-sm text-muted-foreground">
                    No recent news articles available for this state.
                  </CardContent>
                </Card>
              )}
            </div>
          </TabsContent>
        </ScrollArea>
      </Tabs>
    </div>
  );
}

function formatUsd(value: number): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 0,
  }).format(Math.max(0, value || 0));
}

function incumbPartyToMargin(party: 'DEM' | 'REP'): number {
  // Strong default when no general polling exists:
  // negative = DEM lead, positive = REP lead.
  return party === 'DEM' ? -20 : 20;
}

function buildFinanceView(spending: Array<{
  candidateId: string;
  candidateName: string;
  party: 'DEM' | 'REP' | 'OTHER';
  totalFor: number;
}>) {
  const candidates = [...spending]
    .sort((a, b) => b.totalFor - a.totalFor)
    .slice(0, 8);
  const totalSpend = candidates.reduce((sum, c) => sum + (c.totalFor || 0), 0);
  const dem = candidates.filter((c) => c.party === 'DEM').reduce((sum, c) => sum + c.totalFor, 0);
  const rep = candidates.filter((c) => c.party === 'REP').reduce((sum, c) => sum + c.totalFor, 0);
  const ind = candidates.filter((c) => c.party !== 'DEM' && c.party !== 'REP').reduce((sum, c) => sum + c.totalFor, 0);
  const denom = Math.max(totalSpend, 1);
  const partyChart = [
    { key: 'dem', label: 'Democrats', value: dem, share: (dem / denom) * 100, color: '#2563eb' },
    { key: 'rep', label: 'Republicans', value: rep, share: (rep / denom) * 100, color: '#dc2626' },
    { key: 'ind', label: 'Independents', value: ind, share: (ind / denom) * 100, color: '#6b7280' },
  ];
  const partyTotals = [
    { party: 'DEM', label: 'DEM', value: dem, share: (dem / denom) * 100, colorClass: 'text-dem' },
    { party: 'REP', label: 'REP', value: rep, share: (rep / denom) * 100, colorClass: 'text-rep' },
    { party: 'IND', label: 'IND', value: ind, share: (ind / denom) * 100, colorClass: 'text-muted-foreground' },
  ];
  const maxCandidateSpend = Math.max(...candidates.map((c) => c.totalFor), 1);
  return { candidates, totalSpend, partyChart, partyTotals, maxCandidateSpend };
}

function formatPrimaryDate(value: string): string {
  if (!value || value.endsWith("TBD")) {
    return "TBD";
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) {
    return value;
  }

  const year = Number(match[1]);
  const monthIndex = Number(match[2]) - 1;
  const day = Number(match[3]);
  const monthName = new Date(year, monthIndex, day).toLocaleString("en-GB", { month: "long" });
  return `${day}${ordinalSuffix(day)} ${monthName} ${year}`;
}

function ordinalSuffix(day: number): string {
  if (day % 100 >= 11 && day % 100 <= 13) {
    return "th";
  }
  switch (day % 10) {
    case 1:
      return "st";
    case 2:
      return "nd";
    case 3:
      return "rd";
    default:
      return "th";
  }
}

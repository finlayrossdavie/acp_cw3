import { StateData, RaceType } from '@/types/election';
import { formatMaxOneDecimal, getMarginColor } from '@/lib/colorUtils';

interface NationalOverviewProps {
  states: StateData[];
  race: RaceType;
  colorBy?: 'polls' | 'market' | 'incumbent' | 'spending';
}

function SenateContext() {
  return (
    <div className="mb-4 rounded-md border border-border bg-muted/40 p-3 text-xs text-muted-foreground leading-relaxed">
      <p>
        The Senate is made up of 100 seats. Republicans currently hold a 53–47 majority. In the 2026 midterm elections,
        35 seats are up for election with 22 held by Republicans and 13 by Democrats. If Democrats win 17 of those 35
        races, they would secure a 51-seat majority.
      </p>
    </div>
  );
}

export default function NationalOverview({ states, race, colorBy = 'polls' }: NationalOverviewProps) {
  const covered = states.filter((s) => s.covered);
  if (colorBy === 'spending') {
    const totals = covered
      .map((s) => ({ ...s, spendingTotal: s.spendingTotal || 0 }))
      .sort((a, b) => (b.spendingTotal || 0) - (a.spendingTotal || 0));
    const totalSpend = totals.reduce((sum, s) => sum + (s.spendingTotal || 0), 0);
    const topFive = totals.slice(0, 5);
    return (
      <div className="h-full flex flex-col p-4">
        <h2 className="font-heading text-xl font-bold mb-1">National Overview</h2>
        <p className="text-sm text-muted-foreground mb-3">Campaign spending intensity across tracked states</p>
        {race === 'senate' && <SenateContext />}
        <div className="mb-4 border border-border rounded-md p-3">
          <p className="text-xs text-muted-foreground">Total disbursements across tracked states</p>
          <p className="text-lg font-semibold mt-1">{formatUsd(totalSpend)}</p>
        </div>
        <div className="flex-1 min-h-0 overflow-auto">
          <h3 className="font-heading text-sm font-semibold text-muted-foreground uppercase tracking-wide mb-2">
            Highest Spend States
          </h3>
          <div className="space-y-2">
            {topFive.map((state, idx) => (
              <div key={state.abbr} className="border border-border rounded-md p-2">
                <div className="flex justify-between items-center text-sm">
                  <span className="font-medium">{idx + 1}. {state.name}</span>
                  <span className="font-semibold text-emerald-700">{formatUsd(state.spendingTotal || 0)}</span>
                </div>
                <div className="h-2 mt-2 rounded-full bg-muted overflow-hidden">
                  <div
                    className="h-full bg-emerald-600"
                    style={{ width: `${Math.max(2, ((state.spendingTotal || 0) / Math.max(topFive[0]?.spendingTotal || 1, 1)) * 100)}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    );
  }

  const summaryStates = colorBy === 'polls'
    ? covered.filter((s) => s.hasGeneralPolling !== false)
    : covered;
  const marginFor = (s: StateData) =>
    colorBy === 'market' && typeof s.marketMargin === 'number'
      ? s.marketMargin
      : colorBy === 'incumbent' && typeof s.incumbentMargin === 'number'
        ? s.incumbentMargin
        : s.margin;

  const demStates = summaryStates.filter(s => marginFor(s) < -3);
  const repStates = summaryStates.filter(s => marginFor(s) > 3);
  const tossups = summaryStates.filter(s => Math.abs(marginFor(s)) <= 3);
  const demCount = summaryStates.filter(s => marginFor(s) < 0).length;
  const repCount = summaryStates.filter(s => marginFor(s) > 0).length;
  const avgMargin = summaryStates.length
    ? summaryStates.reduce((sum, state) => sum + Math.abs(marginFor(state)), 0) / summaryStates.length
    : 0;

  return (
    <div className="h-full flex flex-col p-4">
      <h2 className="font-heading text-xl font-bold mb-1">National Overview</h2>
      <p className="text-sm text-muted-foreground mb-3">
        {race === 'senate' ? 'Senate races in tracked states' : 'House projections for tracked districts'}
      </p>
      {race === 'senate' && <SenateContext />}

      {/* State count bar */}
      <div className="mb-6">
        <div className="flex justify-between text-sm font-semibold mb-1.5">
          <span className="text-dem">Dem {demCount}</span>
          <span className="text-muted-foreground">{tossups.length > 0 ? `${tossups.length} Toss-up` : ''}</span>
          <span className="text-rep">Rep {repCount}</span>
        </div>
        <div className="h-4 rounded-full bg-muted overflow-hidden flex">
          <div className="bg-dem h-full transition-all duration-500" style={{ width: `${summaryStates.length ? (demCount / summaryStates.length) * 100 : 0}%` }} />
          <div className="bg-tossup h-full transition-all duration-500" style={{ width: `${summaryStates.length ? (tossups.length / summaryStates.length) * 100 : 0}%` }} />
          <div className="bg-rep h-full transition-all duration-500" style={{ width: `${summaryStates.length ? (repCount / summaryStates.length) * 100 : 0}%` }} />
        </div>
        <div className="text-center text-xs text-muted-foreground mt-1">
          {summaryStates.length} tracked states · Avg margin {formatMaxOneDecimal(avgMargin)}
        </div>
      </div>

      {/* Toss-ups section */}
      {tossups.length > 0 && (
        <div className="mb-4">
          <h3 className="font-heading text-sm font-semibold text-muted-foreground uppercase tracking-wide mb-2">
            Toss-up States ({tossups.length})
          </h3>
          <div className="flex flex-wrap gap-1.5">
            {tossups.sort((a, b) => marginFor(a) - marginFor(b)).map(s => {
              const m = marginFor(s);
              return (
              <span
                key={s.abbr}
                className="px-2 py-1 rounded text-xs font-medium border border-border"
                style={{ backgroundColor: getMarginColor(m) + '20', color: getMarginColor(m) }}
              >
                {s.abbr} {m > 0 ? 'R' : m < 0 ? 'D' : ''}{m !== 0 ? `+${formatMaxOneDecimal(Math.abs(m))}` : ''}
              </span>
              );
            })}
          </div>
        </div>
      )}

      {/* Key races */}
      <div className="flex-1 min-h-0 overflow-auto">
        <h3 className="font-heading text-sm font-semibold text-muted-foreground uppercase tracking-wide mb-2">
          Competitive Races
        </h3>
        <div className="space-y-1.5">
          {summaryStates
            .filter(s => Math.abs(marginFor(s)) <= 10)
            .sort((a, b) => Math.abs(marginFor(a)) - Math.abs(marginFor(b)))
            .map(state => (
              <div key={state.abbr} className="flex items-center gap-2 py-1.5 px-2 rounded-md hover:bg-accent/50 text-sm">
                <div className="w-3 h-3 rounded-sm" style={{ backgroundColor: getMarginColor(marginFor(state)) }} />
                <span className="font-medium flex-1">{state.name}</span>
                <span className={`text-xs font-semibold ${marginFor(state) < 0 ? 'text-dem' : 'text-rep'}`}>
                  {marginFor(state) < 0 ? 'D' : 'R'}+{formatMaxOneDecimal(Math.abs(marginFor(state)))}
                </span>
              </div>
            ))}
        </div>
      </div>
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

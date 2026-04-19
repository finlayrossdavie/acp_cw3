import { USAMap } from '@mirawision/usa-map-react';
import { StateData } from '@/types/election';
import { formatMaxOneDecimal, getMarginColor, getMarginLabel } from '@/lib/colorUtils';
import { useState } from 'react';

interface ElectionMapProps {
  states: StateData[];
  selectedState: string | null;
  onStateClick: (abbr: string) => void;
  colorBy: 'polls' | 'market' | 'incumbent' | 'spending';
}

export default function ElectionMap({ states, selectedState, onStateClick, colorBy }: ElectionMapProps) {
  const [hoveredState, setHoveredState] = useState<string | null>(null);
  const [mousePos, setMousePos] = useState({ x: 0, y: 0 });

  const stateMap = Object.fromEntries(states.map(s => [s.abbr, s]));
  const maxSpending = Math.max(...states.map((s) => s.spendingTotal || 0), 1);

  const customStates: Record<string, any> = {};
  states.forEach(state => {
    const isCovered = !!state.covered;
    const isSelected = selectedState === state.abbr;
    const noGeneralPolling = colorBy === 'polls' && isCovered && state.hasGeneralPolling === false;
    const spendingFill =
      colorBy === 'spending'
        ? getSpendingIntensityColor(state.spendingTotal || 0, maxSpending)
        : undefined;
    const marginForColor =
      colorBy === 'market' && typeof state.marketMargin === 'number'
        ? state.marketMargin
        : colorBy === 'incumbent' && typeof state.incumbentMargin === 'number'
          ? state.incumbentMargin
          : state.margin;
    customStates[state.abbr] = {
      fill: !isCovered ? '#ffffff' : colorBy === 'spending' ? spendingFill : noGeneralPolling ? '#d1d5db' : getMarginColor(marginForColor),
      stroke: isSelected && isCovered ? '#0f172a' : '#ffffff',
      strokeWidth: isSelected ? 2 : 0.5,
      onClick: isCovered ? () => onStateClick(state.abbr) : undefined,
      onMouseEnter: () => setHoveredState(state.abbr),
      onMouseLeave: () => setHoveredState(null),
    };
  });

  const hoveredData = hoveredState ? stateMap[hoveredState] : null;

  return (
    <div
      className="relative w-full h-full flex items-center justify-center"
      onMouseMove={(e) => setMousePos({ x: e.clientX, y: e.clientY })}
    >
      <USAMap customStates={customStates} />

      {hoveredData && (
        <div
          className="fixed z-50 pointer-events-none bg-card border border-border rounded-lg shadow-lg px-3 py-2 text-sm"
          style={{ left: mousePos.x + 12, top: mousePos.y - 10 }}
        >
          <div className="font-heading font-semibold">{hoveredData.name}</div>
          {hoveredData.covered ? (
            <>
              <div className="text-muted-foreground text-xs mt-0.5">
                {(() => {
                  if (colorBy === 'spending') {
                    return `Total spend ${formatUsd(hoveredData.spendingTotal || 0)}`;
                  }
                  if (colorBy === 'polls' && hoveredData.hasGeneralPolling === false) {
                    return 'No general election polling available';
                  }
                  const marginForTooltip =
                    colorBy === 'market' && typeof hoveredData.marketMargin === 'number'
                      ? hoveredData.marketMargin
                      : colorBy === 'incumbent' && typeof hoveredData.incumbentMargin === 'number'
                        ? hoveredData.incumbentMargin
                        : hoveredData.margin;
                  return getMarginLabel(marginForTooltip);
                })()}
              </div>
              <div className="flex gap-3 mt-1 text-xs">
                {colorBy === 'spending' ? (
                  <span className="font-medium text-emerald-700">
                    {maxSpending > 0
                      ? `${formatMaxOneDecimal(((hoveredData.spendingTotal || 0) / maxSpending) * 100)}% of max (log-scaled intensity: ${formatMaxOneDecimal(getSpendingIntensityRatio(hoveredData.spendingTotal || 0, maxSpending) * 100)}%)`
                      : 'No spending data'}
                  </span>
                ) : colorBy === 'market' ? (
                  typeof hoveredData.marketDemPercent === 'number' && typeof hoveredData.marketRepPercent === 'number' ? (
                    <>
                      <span className="text-dem font-medium">D {formatMaxOneDecimal(hoveredData.marketDemPercent)}%</span>
                      <span className="text-rep font-medium">R {formatMaxOneDecimal(hoveredData.marketRepPercent)}%</span>
                    </>
                  ) : (
                    <span className="text-muted-foreground">Market odds unavailable</span>
                  )
                ) : colorBy === 'incumbent' ? (
                  <span className={`font-medium ${hoveredData.incumbentParty === 'DEM' ? 'text-dem' : 'text-rep'}`}>
                    Current seat: {hoveredData.incumbentParty === 'DEM' ? 'Democrat' : 'Republican'}
                  </span>
                ) : (
                  hoveredData.hasGeneralPolling === false ? (
                    <span className="text-muted-foreground">No general polling data</span>
                  ) : (
                    <>
                      <span className="text-dem font-medium">D {formatMaxOneDecimal(hoveredData.demPercent)}%</span>
                      <span className="text-rep font-medium">R {formatMaxOneDecimal(hoveredData.repPercent)}%</span>
                    </>
                  )
                )}
              </div>
            </>
          ) : (
            <div className="text-muted-foreground text-xs mt-1">Not in current dataset</div>
          )}
        </div>
      )}
    </div>
  );
}

function getSpendingIntensityColor(total: number, max: number): string {
  if (!total || total <= 0 || max <= 0) {
    return '#f3f4f6';
  }
  const ratio = getSpendingIntensityRatio(total, max);
  if (ratio < 0.1) return '#f0fdf4';
  if (ratio < 0.2) return '#dcfce7';
  if (ratio < 0.32) return '#bbf7d0';
  if (ratio < 0.46) return '#86efac';
  if (ratio < 0.62) return '#4ade80';
  if (ratio < 0.75) return '#22c55e';
  if (ratio < 0.86) return '#16a34a';
  if (ratio < 0.94) return '#15803d';
  return '#14532d';
}

function getSpendingIntensityRatio(total: number, max: number): number {
  if (!total || total <= 0 || max <= 0) {
    return 0;
  }
  // Aggressive battleground scaling:
  // - log component keeps low/mid states distinguishable
  // - root-linear component amplifies separation among top spend states
  //   (e.g. TX 60m vs NC 20m no longer looks nearly identical)
  const logNorm = Math.log10(total + 1) / Math.log10(max + 1);
  const linearNorm = total / max;
  const boostedTopEnd = Math.sqrt(Math.max(0, Math.min(1, linearNorm)));
  const hybrid = 0.45 * logNorm + 0.55 * boostedTopEnd;
  return Math.max(0, Math.min(1, hybrid));
}

function formatUsd(value: number): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 0,
  }).format(Math.max(0, value || 0));
}

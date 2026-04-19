import { useState } from 'react';
import { useMapData, useSpendingMapData } from '@/hooks/useElectionData';
import ElectionMap from '@/components/ElectionMap';
import StateDetailPanel from '@/components/StateDetailPanel';
import NationalOverview from '@/components/NationalOverview';
import { motion, AnimatePresence } from 'framer-motion';

export default function Index() {
  const race = 'senate' as const;
  const [selectedState, setSelectedState] = useState<string | null>(null);
  const [colorBy, setColorBy] = useState<'polls' | 'market' | 'incumbent' | 'spending'>('polls');
  const { data: baseStates = [] } = useMapData(race);
  const { data: spendingStates = [] } = useSpendingMapData(race, colorBy === 'spending');
  const states = colorBy === 'spending' ? (spendingStates.length > 0 ? spendingStates : baseStates) : baseStates;

  const selectedData = states.find(s => s.abbr === selectedState);
  const coveredStates = states.filter(s => s.covered);

  const pollingSummaryStates = coveredStates.filter((s) => s.hasGeneralPolling !== false);
  const demCount = pollingSummaryStates.filter(s => s.margin < 0).length;
  const repCount = pollingSummaryStates.filter(s => s.margin > 0).length;
  const tossupCount = pollingSummaryStates.filter(s => s.margin === 0).length;
  const maxSpend = Math.max(...coveredStates.map((s) => s.spendingTotal || 0), 1);
  const highSpend = coveredStates.filter((s) => (s.spendingTotal || 0) >= maxSpend * 0.6).length;
  const mediumSpend = coveredStates.filter((s) => (s.spendingTotal || 0) >= maxSpend * 0.25 && (s.spendingTotal || 0) < maxSpend * 0.6).length;
  const lowSpend = coveredStates.filter((s) => (s.spendingTotal || 0) < maxSpend * 0.25).length;

  return (
    <div className="h-screen flex flex-col bg-background">
      {/* Top Bar */}
      <header className="border-b border-border bg-card px-4 py-3 flex items-center justify-between flex-shrink-0">
        <div className="flex items-center gap-4">
          <h1 className="font-heading text-lg font-bold tracking-tight">
            2026 MidTerm Tracker
          </h1>
          <div className="hidden sm:block text-xs uppercase tracking-wider text-muted-foreground">Senate</div>
        </div>

        <div className="flex items-center gap-4 text-sm">
          <div className="flex items-center gap-2">
            <span className="text-xs text-muted-foreground">Color by</span>
            <button
              type="button"
              onClick={() => setColorBy('polls')}
              className={`px-2 py-1 rounded-md text-xs border ${
                colorBy === 'polls' ? 'bg-accent/50 border-border' : 'bg-card border-border text-muted-foreground'
              }`}
            >
              Polls
            </button>
            <button
              type="button"
              onClick={() => setColorBy('market')}
              className={`px-2 py-1 rounded-md text-xs border ${
                colorBy === 'market' ? 'bg-accent/50 border-border' : 'bg-card border-border text-muted-foreground'
              }`}
            >
              Market
            </button>
            <button
              type="button"
              onClick={() => setColorBy('incumbent')}
              className={`px-2 py-1 rounded-md text-xs border ${
                colorBy === 'incumbent' ? 'bg-accent/50 border-border' : 'bg-card border-border text-muted-foreground'
              }`}
            >
              Current Seat
            </button>
            <button
              type="button"
              onClick={() => setColorBy('spending')}
              className={`px-2 py-1 rounded-md text-xs border ${
                colorBy === 'spending' ? 'bg-accent/50 border-border' : 'bg-card border-border text-muted-foreground'
              }`}
            >
              Spending
            </button>
          </div>

          {colorBy === 'spending' ? (
            <>
              <div className="flex items-center gap-1.5">
                <div className="w-2.5 h-2.5 rounded-full bg-emerald-600" />
                <span className="font-medium">{highSpend}</span>
              </div>
              <div className="flex items-center gap-1.5">
                <div className="w-2.5 h-2.5 rounded-full bg-emerald-400" />
                <span className="font-medium">{mediumSpend}</span>
              </div>
              <div className="flex items-center gap-1.5">
                <div className="w-2.5 h-2.5 rounded-full bg-emerald-100" />
                <span className="font-medium">{lowSpend}</span>
              </div>
            </>
          ) : (
            <>
              <div className="flex items-center gap-1.5">
                <div className="w-2.5 h-2.5 rounded-full bg-dem" />
                <span className="font-medium">{demCount}</span>
              </div>
              <div className="flex items-center gap-1.5">
                <div className="w-2.5 h-2.5 rounded-full bg-rep" />
                <span className="font-medium">{repCount}</span>
              </div>
              {tossupCount > 0 && (
                <div className="flex items-center gap-1.5">
                  <div className="w-2.5 h-2.5 rounded-full bg-tossup" />
                  <span className="font-medium">{tossupCount}</span>
                </div>
              )}
            </>
          )}
        </div>
      </header>

      {/* Main split layout */}
      <div className="flex-1 flex flex-col lg:flex-row min-h-0 overflow-hidden">
        {/* Map Panel */}
        <div className="flex-1 lg:w-[60%] min-h-[300px] lg:min-h-0 p-2 lg:p-4 overflow-hidden">
          <div className="w-full h-full rounded-xl bg-card border border-border shadow-sm overflow-hidden flex items-center justify-center p-4">
            <ElectionMap
              states={states}
              selectedState={selectedState}
              onStateClick={(abbr) => setSelectedState(abbr === selectedState ? null : abbr)}
              colorBy={colorBy}
            />
          </div>
        </div>

        {/* Detail Panel */}
        <div className="lg:w-[40%] border-t lg:border-t-0 lg:border-l border-border bg-card overflow-hidden">
          <AnimatePresence mode="wait">
            {selectedData ? (
              <motion.div
                key={selectedData.abbr}
                initial={{ opacity: 0, x: 20 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: -20 }}
                transition={{ duration: 0.2 }}
                className="h-full"
              >
                <StateDetailPanel
                  stateAbbr={selectedData.abbr}
                  stateName={selectedData.name}
                  margin={selectedData.margin}
                  hasGeneralPolling={selectedData.hasGeneralPolling}
                  incumbentParty={selectedData.incumbentParty}
                  race={race}
                  onBack={() => setSelectedState(null)}
                />
              </motion.div>
            ) : (
              <motion.div
                key="overview"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 0.2 }}
                className="h-full"
              >
                <NationalOverview states={states} race={race} colorBy={colorBy} />
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </div>
    </div>
  );
}

import React, { useEffect, useState } from "react";
import { getStateDetail, getStates } from "./api/client";
import MapView from "./components/MapView";
import StateDetailPanel from "./components/StateDetailPanel";

export default function App() {
  const [summaries, setSummaries] = useState([]);
  const [selectedState, setSelectedState] = useState(null);
  const [detail, setDetail] = useState(null);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    getStates()
      .then((data) => setSummaries(data))
      .catch(() => setError("Could not load state summaries from API."));
  }, []);

  useEffect(() => {
    if (!selectedState) return;
    setLoadingDetail(true);
    setError("");
    getStateDetail(selectedState)
      .then((data) => setDetail(data))
      .catch(() => setError("Could not load state detail."))
      .finally(() => setLoadingDetail(false));
  }, [selectedState]);

  return (
    <main className="layout">
      <section>
        <h1>Election Projection Map</h1>
        <MapView summaries={summaries} selectedState={selectedState} onSelect={setSelectedState} />
      </section>
      <StateDetailPanel detail={detail} loading={loadingDetail} error={error} />
    </main>
  );
}

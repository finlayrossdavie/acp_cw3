import React from "react";

export default function StateDetailPanel({ detail, loading, error }) {
  if (loading) return <div className="panel">Loading state details...</div>;
  if (error) return <div className="panel error">{error}</div>;
  if (!detail) return <div className="panel">Select a state to view details.</div>;

  return (
    <div className="panel">
      <h2>{detail.state} Senate Race</h2>
      <p>
        Leader: <strong>{detail.leadingParty}</strong>
      </p>
      <p>
        Margin: <strong>{detail.margin.toFixed(2)}</strong>
      </p>
      <p>
        Projection DEM/REP:{" "}
        <strong>
          {detail.projection.demAvg.toFixed(1)} / {detail.projection.repAvg.toFixed(1)}
        </strong>
      </p>
      <p>
        Source: <strong>{detail.sourceType}</strong>
      </p>
      <p>
        Updated: <strong>{new Date(detail.updatedAt).toLocaleString()}</strong>
      </p>
      <h3>Prediction Market Odds</h3>
      {detail.odds ? (
        <>
          <p>
            DEM: <strong>{(detail.odds.demProbability * 100).toFixed(1)}%</strong>
          </p>
          <p>
            REP: <strong>{(detail.odds.repProbability * 100).toFixed(1)}%</strong>
          </p>
          <div
            style={{
              width: "100%",
              height: "12px",
              background: "#e5e7eb",
              borderRadius: "999px",
              overflow: "hidden",
              display: "flex",
            }}
          >
            <div
              style={{
                width: `${Math.max(0, Math.min(100, detail.odds.demProbability * 100))}%`,
                background: "#2563eb",
              }}
            />
            <div
              style={{
                width: `${Math.max(0, Math.min(100, detail.odds.repProbability * 100))}%`,
                background: "#dc2626",
              }}
            />
          </div>
        </>
      ) : (
        <p>Prediction market odds unavailable.</p>
      )}
      <h3>News</h3>
      <ul>
        {(detail.news || []).map((article, idx) => (
          <li key={`${article.url}-${idx}`}>
            <a href={article.url} target="_blank" rel="noreferrer">
              {article.title}
            </a>{" "}
            ({article.source})
          </li>
        ))}
      </ul>
    </div>
  );
}

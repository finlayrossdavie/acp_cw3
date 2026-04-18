import React from "react";

const STATE_LAYOUT = [
  { code: "NC", x: 300, y: 150 },
  { code: "GA", x: 250, y: 200 },
  { code: "ME", x: 380, y: 40 },
  { code: "MI", x: 230, y: 80 },
  { code: "TX", x: 140, y: 240 },
];

export default function MapView({ summaries, selectedState, onSelect }) {
  const colorByState = Object.fromEntries(summaries.map((s) => [s.state, s.color]));

  return (
    <svg width="500" height="320" viewBox="0 0 500 320">
      <rect x="0" y="0" width="500" height="320" fill="#f2f2f2" />
      {STATE_LAYOUT.map((state) => (
        <g key={state.code} onClick={() => onSelect(state.code)} style={{ cursor: "pointer" }}>
          <rect
            x={state.x}
            y={state.y}
            width="70"
            height="40"
            fill={colorByState[state.code] || "#bdbdbd"}
            stroke={selectedState === state.code ? "#111" : "#666"}
            strokeWidth={selectedState === state.code ? "3" : "1"}
          />
          <text x={state.x + 35} y={state.y + 24} textAnchor="middle" fontSize="14" fill="#111">
            {state.code}
          </text>
        </g>
      ))}
    </svg>
  );
}

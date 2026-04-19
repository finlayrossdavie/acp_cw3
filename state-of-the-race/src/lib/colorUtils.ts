/**
 * Returns a hex color on the gradient: deep blue → light blue → purple → light red → deep red
 * based on margin (negative = Dem, positive = Rep)
 */
export function getMarginColor(margin: number): string {
  // Clamp margin to -30..+30 for color scaling
  const clamped = Math.max(-30, Math.min(30, margin));
  const t = (clamped + 30) / 60; // 0 = deep blue, 1 = deep red

  // Color stops: deep blue, light blue, purple, light red, deep red
  const stops = [
    { pos: 0, r: 37, g: 99, b: 235 },    // #2563EB deep blue
    { pos: 0.3, r: 96, g: 165, b: 250 },  // #60A5FA light blue
    { pos: 0.5, r: 147, g: 100, b: 200 }, // purple tossup
    { pos: 0.7, r: 248, g: 113, b: 113 }, // #F87171 light red
    { pos: 1, r: 220, g: 38, b: 38 },     // #DC2626 deep red
  ];

  // Find the two stops we're between
  let lower = stops[0];
  let upper = stops[stops.length - 1];
  for (let i = 0; i < stops.length - 1; i++) {
    if (t >= stops[i].pos && t <= stops[i + 1].pos) {
      lower = stops[i];
      upper = stops[i + 1];
      break;
    }
  }

  const range = upper.pos - lower.pos;
  const localT = range === 0 ? 0 : (t - lower.pos) / range;

  const r = Math.round(lower.r + (upper.r - lower.r) * localT);
  const g = Math.round(lower.g + (upper.g - lower.g) * localT);
  const b = Math.round(lower.b + (upper.b - lower.b) * localT);

  return `#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}`;
}

export function getMarginLabel(margin: number): string {
  const abs = Math.abs(margin);
  const formatted = formatMaxOneDecimal(abs);
  const party = margin < 0 ? 'D' : margin > 0 ? 'R' : '';
  if (abs === 0) return 'Toss-up';
  if (abs <= 3) return `Toss-up (${party}+${formatted})`;
  if (abs <= 8) return `Lean ${party} +${formatted}`;
  if (abs <= 15) return `Likely ${party} +${formatted}`;
  return `Safe ${party} +${formatted}`;
}

export function formatMaxOneDecimal(value: number): string {
  const rounded = Math.round(value * 10) / 10;
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1);
}


## US Election Tracker — UI Plan

### Design System
- **Palette**: Cloud White — crisp whites (#fafbfc), soft grays (#e8ecf1, #94a3b8), blue accent (#3b82f6). Red (#DC2626) and Blue (#2563EB) for party colors with gradient shading through purple for swing states.
- **Typography**: Space Grotesk headings, DM Sans body text.
- **Layout**: Split-screen — interactive map on the left, detail panel on the right.
- **Border radius**: 12px cards, smooth subtle shadows.

### Pages & Components

**1. Main Election Tracker (Index page)**
- **Top bar**: Title "US Election Tracker 2024", Senate/House toggle switch, overall summary stats (e.g. projected seat counts).
- **Left panel (~60%)**: Interactive SVG map of all 50 US states. States colored on a gradient scale: deep blue → light blue → purple → light red → deep red based on polling margin from backend. Hover shows state name + quick poll summary tooltip.
- **Right panel (~40%)**: Defaults to a national overview. When a state is clicked, the map smoothly zooms into that state and the right panel updates to show:
  - **State header**: State name, electoral votes, current lean indicator
  - **Polls tab**: Average of different polls displayed as a bar comparison + list of individual polls with pollster name, date, and margin
  - **Betting tab**: Betting market odds shown as a visual gauge/bar
  - **News tab**: Scrollable list of recent news article cards (title, source, date, snippet)
- **Back button** on zoomed state to return to national view.

**2. Senate vs House Toggle**
- Toggle in the top bar switches the entire map and data context between Senate and House races.
- Map colors update accordingly. Right panel data reflects the selected race type.

**3. Responsive Behavior**
- On mobile, stacks vertically: map on top, detail panel below.
- Map becomes scrollable/pannable on touch.

### Data Integration
- All data fetched from your existing Java Spring Boot backend via REST API calls.
- Components will use React Query to fetch: state polling data, betting odds, news articles.
- API endpoints expected (configurable): `GET /api/polls/{race}/{state}`, `GET /api/odds/{race}/{state}`, `GET /api/news/{state}`, `GET /api/map/{race}` (returns all state colors/margins).

### Key Libraries
- Custom SVG map component using US state paths (no heavy map library needed).
- Framer Motion for smooth zoom transitions.
- React Query for data fetching with caching.

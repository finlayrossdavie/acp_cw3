"""
Lightweight OpenFEC exploration script (no project integration).

Purpose:
- Find Senate candidates for a state/cycle
- Pull candidate committee spending totals
- Pull independent expenditure totals (support/oppose)
- Print a per-candidate spending summary

Run examples:
  python backend/test_open_fec.py --state NC --cycle 2026
  python backend/test_open_fec.py --state GA --cycle 2026 --query ossoff

Requires:
- OPEN_FEC_API_KEY in environment (loaded from shell/.env)
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.parse
import urllib.request
from urllib.error import HTTPError
from typing import Any, Dict, List


BASE_URL = "https://api.open.fec.gov/v1"


def get_json(path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    query = urllib.parse.urlencode(params, doseq=True)
    url = f"{BASE_URL}{path}?{query}"
    req = urllib.request.Request(url, headers={"User-Agent": "CW3-FEC-Test/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except HTTPError as exc:
        body = exc.read().decode("utf-8", errors="ignore")
        raise RuntimeError(f"OpenFEC HTTP {exc.code} for {url}\n{body}") from exc


def search_candidates(api_key: str, state: str, cycle: int, office: str, query: str | None) -> List[Dict[str, Any]]:
    params: Dict[str, Any] = {
        "api_key": api_key,
        "state": state,
        "cycle": cycle,
        "office": office,
        "per_page": 20,
        "sort": "-receipts",
    }
    if query:
        params["q"] = query
    data = get_json("/candidates/search/", params)
    results = data.get("results", [])
    # Keep likely active/real candidates first.
    return [
        c
        for c in results
        if c.get("candidate_id")
        and c.get("has_raised_funds", False)
        and c.get("candidate_status") in {"C", "N", "P"}
    ]


def candidate_totals(api_key: str, candidate_id: str, cycle: int) -> Dict[str, Any] | None:
    data = get_json(
        f"/candidate/{candidate_id}/totals/",
        {"api_key": api_key, "cycle": cycle, "per_page": 20},
    )
    results = data.get("results", [])
    if not results:
        return None
    # For this cycle there is typically one row.
    return results[0]


def independent_expenditure_totals(api_key: str, candidate_id: str, cycle: int) -> Dict[str, float]:
    data = get_json(
        "/schedules/schedule_e/by_candidate/",
        {"api_key": api_key, "cycle": cycle, "candidate_id": candidate_id, "per_page": 20},
    )
    support = 0.0
    oppose = 0.0
    for row in data.get("results", []):
        amount = float(row.get("total") or 0.0)
        side = (row.get("support_oppose_indicator") or "").upper()
        if side == "S":
            support += amount
        elif side == "O":
            oppose += amount
    return {"support": support, "oppose": oppose}


def fmt_usd(v: float) -> str:
    return f"${v:,.2f}"


def main() -> int:
    parser = argparse.ArgumentParser(description="Explore OpenFEC candidate spending for a race")
    parser.add_argument("--state", required=True, help="State abbreviation (e.g. NC)")
    parser.add_argument("--cycle", type=int, default=2026, help="Election cycle year (default: 2026)")
    parser.add_argument("--office", default="S", help="Office code (S=Senate, H=House, P=President)")
    parser.add_argument("--query", default=None, help="Optional name filter (e.g. 'whatley')")
    args = parser.parse_args()

    api_key = os.getenv("OPEN_FEC_API_KEY", "").strip()
    if not api_key:
        print("Missing OPEN_FEC_API_KEY in environment.")
        return 1

    state = args.state.strip().upper()
    candidates = search_candidates(api_key, state, args.cycle, args.office, args.query)
    if not candidates:
        print(f"No candidates found for state={state}, cycle={args.cycle}, office={args.office}")
        return 0

    print(f"\nOpenFEC spending snapshot for {state} {args.cycle} office={args.office}\n")
    for cand in candidates:
        candidate_id = cand["candidate_id"]
        name = cand.get("name", candidate_id)
        party = cand.get("party", "")
        totals = candidate_totals(api_key, candidate_id, args.cycle)
        ie = independent_expenditure_totals(api_key, candidate_id, args.cycle)

        committee_disbursements = float((totals or {}).get("disbursements") or 0.0)
        committee_receipts = float((totals or {}).get("receipts") or 0.0)
        cash_on_hand = float((totals or {}).get("last_cash_on_hand_end_period") or 0.0)

        # Useful derived views:
        total_spending_for_candidate = committee_disbursements + ie["support"]
        spending_pressure_against_candidate = ie["oppose"]

        print(f"{name} ({party}) [{candidate_id}]")
        print(f"  Committee receipts:       {fmt_usd(committee_receipts)}")
        print(f"  Committee disbursements:  {fmt_usd(committee_disbursements)}")
        print(f"  Cash on hand:             {fmt_usd(cash_on_hand)}")
        print(f"  IE support:               {fmt_usd(ie['support'])}")
        print(f"  IE oppose:                {fmt_usd(ie['oppose'])}")
        print(f"  Total 'for' spend:        {fmt_usd(total_spending_for_candidate)}")
        print(f"  Total 'against' pressure: {fmt_usd(spending_pressure_against_candidate)}")
        print()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())


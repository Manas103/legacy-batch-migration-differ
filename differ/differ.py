#!/usr/bin/env python3
"""Parallel-run differ.

Takes one fixed-width EOD position file, replays it through both the
legacy Python batch and the new Spring Boot service (each already run
separately, see build_and_run.sh / the README), and compares every derived
field of every record: market_value and settle_date. Reports every
discrepancy with record id, field name, legacy value, new value.

Usage:
    python differ/differ.py <legacy_output.csv> <java_output.csv> [report.txt]

Both CSVs are produced independently by legacy/legacy_batch.py and the
BatchMigrationApplication jar against the *same* input file; this script
does not re-run either implementation, it only compares their outputs, so
it can be re-run cheaply against any pair of output files.
"""
from __future__ import annotations

import csv
import sys


def load(path: str) -> dict[int, dict[str, str]]:
    rows: dict[int, dict[str, str]] = {}
    with open(path, "r", newline="", encoding="ascii") as f:
        for row in csv.DictReader(f):
            rows[int(row["record_id"])] = row
    return rows


COMPARE_FIELDS = ["account_id", "isin", "currency", "market_value", "settle_date"]


def diff(legacy: dict[int, dict[str, str]], new: dict[int, dict[str, str]]):
    discrepancies = []
    legacy_ids = set(legacy.keys())
    new_ids = set(new.keys())

    missing_in_new = sorted(legacy_ids - new_ids)
    missing_in_legacy = sorted(new_ids - legacy_ids)
    for rid in missing_in_new:
        discrepancies.append((rid, "record", "present", "MISSING"))
    for rid in missing_in_legacy:
        discrepancies.append((rid, "record", "MISSING", "present"))

    for rid in sorted(legacy_ids & new_ids):
        lrow, nrow = legacy[rid], new[rid]
        for field in COMPARE_FIELDS:
            lval, nval = lrow[field], nrow[field]
            if lval != nval:
                discrepancies.append((rid, field, lval, nval))
    return discrepancies


def main() -> None:
    if len(sys.argv) not in (3, 4):
        print("usage: differ.py <legacy_output.csv> <java_output.csv> [report.txt]", file=sys.stderr)
        sys.exit(2)
    legacy_path, new_path = sys.argv[1], sys.argv[2]
    report_path = sys.argv[3] if len(sys.argv) == 4 else None

    legacy = load(legacy_path)
    new = load(new_path)
    discrepancies = diff(legacy, new)

    lines = []
    lines.append(f"differ: comparing {len(legacy)} legacy record(s) against {len(new)} new-service record(s)")
    lines.append(f"fields compared per record: {', '.join(COMPARE_FIELDS)}")
    lines.append("")
    if not discrepancies:
        lines.append("RESULT: 0 discrepancies. Full equivalence over every compared record and field.")
    else:
        lines.append(f"RESULT: {len(discrepancies)} discrepancy(ies) found.")
        lines.append("")
        lines.append(f"{'record_id':>10}  {'field':<14} {'legacy':<20} {'new':<20}")
        for rid, field, lval, nval in discrepancies:
            lines.append(f"{rid:>10}  {field:<14} {lval:<20} {nval:<20}")

    output = "\n".join(lines)
    print(output)
    if report_path:
        with open(report_path, "w", encoding="ascii") as f:
            f.write(output + "\n")

    sys.exit(0 if not discrepancies else 1)


if __name__ == "__main__":
    main()

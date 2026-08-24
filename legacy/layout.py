"""Fixed-width field offsets for the EOD position file.

Mirrors docs/layout.md exactly. Columns below are 0-indexed, end-exclusive,
Python-slice style (docs/layout.md states them 1-indexed inclusive, the
convention a fixed-width spec is normally written in).
"""

FIELDS = {
    "account_id": (0, 10),
    "isin": (10, 22),
    "quantity": (22, 37),
    "price": (37, 52),
    "currency": (52, 55),
    "trade_date": (55, 63),
    "settle_cycle_days": (63, 65),
}

RECORD_WIDTH = 65


def slice_field(line: str, name: str) -> str:
    start, end = FIELDS[name]
    return line[start:end]

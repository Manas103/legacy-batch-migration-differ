#!/usr/bin/env python3
"""Deterministic synthetic EOD position file generator.

Regenerate with:
    python legacy/generate_input.py data/eod_positions_250000.txt 250000

Seed is fixed, so the same command always produces byte-identical output.
The bulk of the file is realistic random data across four currencies and a
7+ year date range, restricted to real business days for trade_date (an EOD
extract is only produced on days the business actually trades). A small,
deliberately placed set of records is layered on top to exercise the two
mechanisms this repository's whole premise depends on: rounding-mode
sensitivity (an unrounded market value landing exactly on a half-boundary
with an even digit ahead of it) and date-convention sensitivity (a trade
date that is itself not a business day, forcing a decision about where the
settlement clock starts). See docs/layout.md for the field spec and the
README for what these actually produced.
"""
from __future__ import annotations

import datetime
import decimal
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from legacy_batch import is_business_day  # noqa: E402

SEED = 20260601
CURRENCIES = ["USD", "USD", "USD", "EUR", "GBP", "JPY"]  # USD weighted heavier, realistic
RANGE_START = datetime.date(2019, 1, 1)
RANGE_END = datetime.date(2026, 5, 29)

ACCOUNT_POOL_SIZE = 4000
ISIN_POOL_SIZE = 600


def build_account_pool(rng: random.Random) -> list[str]:
    return [f"ACC{i:07d}" for i in range(1, ACCOUNT_POOL_SIZE + 1)]


def build_isin_pool(rng: random.Random) -> list[str]:
    countries = ["US", "GB", "DE", "FR", "JP", "NL", "CH", "CA"]
    pool = []
    for _ in range(ISIN_POOL_SIZE):
        country = rng.choice(countries)
        body = "".join(rng.choice("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ") for _ in range(9))
        check = rng.choice("0123456789")
        pool.append(f"{country}{body}{check}")
    return pool


def random_business_day(rng: random.Random) -> datetime.date:
    span = (RANGE_END - RANGE_START).days
    while True:
        d = RANGE_START + datetime.timedelta(days=rng.randint(0, span))
        if is_business_day(d):
            return d


def first_saturday_on_or_after(d: datetime.date) -> datetime.date:
    while d.weekday() != 5:
        d += datetime.timedelta(days=1)
    return d


def first_sunday_on_or_after(d: datetime.date) -> datetime.date:
    while d.weekday() != 6:
        d += datetime.timedelta(days=1)
    return d


def fmt_qty(q: decimal.Decimal) -> str:
    return f"{q:.4f}".rjust(15)


def fmt_price(p: decimal.Decimal) -> str:
    return f"{p:.6f}".rjust(15)


def make_line(account_id: str, isin: str, quantity: decimal.Decimal, price: decimal.Decimal,
              currency: str, trade_date: datetime.date, settle_cycle_days: int) -> str:
    line = (
        account_id.ljust(10)
        + isin.ljust(12)
        + fmt_qty(quantity)
        + fmt_price(price)
        + currency.ljust(3)
        + trade_date.strftime("%Y%m%d")
        + f"{settle_cycle_days:02d}"
    )
    assert len(line) == 65, f"record width is {len(line)}, expected 65: {line!r}"
    return line


def random_record(rng: random.Random, accounts: list[str], isins: list[str]) -> str:
    account_id = rng.choice(accounts)
    isin = rng.choice(isins)
    currency = rng.choice(CURRENCIES)
    # Occasional short position (negative quantity) and the rare flat/closed
    # position (zero quantity), both real states in a position file.
    roll = rng.random()
    if roll < 0.08:
        quantity = decimal.Decimal(0)
    elif roll < 0.20:
        quantity = -decimal.Decimal(rng.randint(1, 500_000)).scaleb(-4)
    else:
        quantity = decimal.Decimal(rng.randint(1, 500_000)).scaleb(-4)
    price = decimal.Decimal(rng.randint(1, 500_000_000)).scaleb(-6)
    trade_date = random_business_day(rng)
    settle_cycle_days = rng.choices([0, 1, 2, 3], weights=[5, 15, 60, 20])[0]
    return make_line(account_id, isin, quantity, price, currency, trade_date, settle_cycle_days)


def rounding_boundary_records(accounts: list[str], isins: list[str]) -> list[str]:
    """Two records whose unrounded market value lands exactly on a
    round-half boundary with an even digit ahead of it, one per minor-unit
    precision present in the dataset (USD = 2 decimals, JPY = 0 decimals).
    ROUND_HALF_UP and ROUND_HALF_EVEN disagree on exactly this shape of
    input; anywhere else they agree."""
    records = []
    # USD: 1 * 12340.005000 -> unrounded 12340.005000, cents digit '0' is even.
    records.append(make_line(
        accounts[0], isins[0], decimal.Decimal("1.0000"), decimal.Decimal("12340.005000"),
        "USD", datetime.date(2022, 6, 15), 2,
    ))
    # JPY: 1 * 45820.500000 -> unrounded 45820.500000, units digit '0' is even.
    records.append(make_line(
        accounts[1], isins[1], decimal.Decimal("1.0000"), decimal.Decimal("45820.500000"),
        "JPY", datetime.date(2023, 9, 12), 1,
    ))
    return records


def date_convention_boundary_records(accounts: list[str], isins: list[str]) -> list[str]:
    """One record whose trade_date is itself a Saturday: a late or
    erroneous booking landing on a non-business day. Whether the settlement
    clock is normalized to the next business day before counting, or the
    walk simply starts from the raw calendar date, is exactly the rule
    docs/layout.md calls out as easy to miss."""
    saturday = first_saturday_on_or_after(datetime.date(2023, 3, 1))
    return [make_line(
        accounts[2], isins[2], decimal.Decimal("2500.0000"), decimal.Decimal("88.250000"),
        "USD", saturday, 2,
    )]


def month_end_and_leap_records(rng: random.Random, accounts: list[str], isins: list[str]) -> list[str]:
    """Records that land on genuinely interesting calendar dates (leap day,
    month ends, a year boundary) without being engineered to disagree; these
    exercise the date-convention code path at volume without being the
    controlled boundary cases above."""
    dates = [
        datetime.date(2020, 2, 28),  # day before leap day, 2020 is a real leap year
        datetime.date(2020, 3, 2),   # first business day after leap day
        datetime.date(2024, 2, 28),
        datetime.date(2024, 3, 1),
        datetime.date(2019, 1, 31),  # month end
        datetime.date(2021, 12, 31),  # year end
        datetime.date(2022, 4, 29),  # month end, Friday
    ]
    out = []
    for i, d in enumerate(dates):
        if not is_business_day(d):
            continue
        out.append(make_line(
            accounts[3 + i], isins[3 + i],
            decimal.Decimal(rng.randint(1, 10000)).scaleb(-4),
            decimal.Decimal(rng.randint(1, 10_000_000)).scaleb(-6),
            rng.choice(CURRENCIES), d, rng.choice([1, 2, 3]),
        ))
    return out


def main() -> None:
    if len(sys.argv) != 3:
        print("usage: generate_input.py <output_path> <record_count>", file=sys.stderr)
        sys.exit(2)
    output_path = Path(sys.argv[1])
    count = int(sys.argv[2])

    rng = random.Random(SEED)
    accounts = build_account_pool(rng)
    isins = build_isin_pool(rng)

    special = rounding_boundary_records(accounts, isins) + date_convention_boundary_records(accounts, isins) \
        + month_end_and_leap_records(rng, accounts, isins)

    bulk_count = count - len(special)
    lines = [random_record(rng, accounts, isins) for _ in range(bulk_count)]

    # Interleave the special records at fixed, evenly spaced positions so
    # they are not all clustered at the end of the file.
    step = max(1, len(lines) // (len(special) + 1))
    for i, rec in enumerate(special):
        insert_at = min(len(lines), step * (i + 1))
        lines.insert(insert_at, rec)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="ascii", newline="\n") as f:
        for line in lines:
            f.write(line + "\n")

    print(f"generate_input: wrote {len(lines)} record(s) ({len(special)} deliberately placed boundary "
          f"records) to {output_path}")


if __name__ == "__main__":
    main()

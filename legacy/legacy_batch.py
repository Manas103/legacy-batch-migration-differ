#!/usr/bin/env python3
"""The "legacy" EOD position batch, reimplemented as a Spring Boot service
on the Java side of this repository.

Reads a fixed-width position file (docs/layout.md) and, for every record,
computes two derived fields: market value (quantity * price, rounded to the
currency's minor-unit precision) and the contractual settlement date
(trade date plus N business days, skipping weekends and a fixed observed
holiday calendar). Writes one CSV row per input record.

This script represents "the way it's always been done": a straightforward,
single-file, decimal.Decimal-based implementation with no framework. It is
read by the differ (differ/differ.py) as one of the two paths being compared,
never by the Java side.
"""
from __future__ import annotations

import csv
import datetime
import decimal
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from layout import slice_field  # noqa: E402

MINOR_UNITS = {"USD": 2, "EUR": 2, "GBP": 2, "JPY": 0}

# decimal.Decimal's context default rounding is ROUND_HALF_EVEN; this batch
# has always rounded half up (round half away from zero on the positive
# side), which is the convention the source COBOL system used, so that is
# set explicitly rather than relying on whatever the ambient context is.
decimal.getcontext().rounding = decimal.ROUND_HALF_UP


def _observed(d: datetime.date) -> datetime.date:
    """Shift a holiday that falls on a weekend to the observed business day:
    Saturday -> preceding Friday, Sunday -> following Monday."""
    if d.weekday() == 5:  # Saturday
        return d - datetime.timedelta(days=1)
    if d.weekday() == 6:  # Sunday
        return d + datetime.timedelta(days=1)
    return d


def holidays_for_year(year: int) -> set[datetime.date]:
    days = {
        _observed(datetime.date(year, 1, 1)),
        _observed(datetime.date(year, 7, 4)),
        _observed(datetime.date(year, 12, 25)),
    }
    # New Year's observance can shift backward into December of the
    # previous year when it falls on a Saturday; a lookup keyed by that
    # December date must still find it.
    next_new_years = datetime.date(year + 1, 1, 1)
    if next_new_years.weekday() == 5:  # Saturday
        days.add(next_new_years - datetime.timedelta(days=1))
    return days


_holiday_cache: dict[int, set[datetime.date]] = {}


def is_business_day(d: datetime.date) -> bool:
    if d.weekday() >= 5:
        return False
    holidays = _holiday_cache.setdefault(d.year, holidays_for_year(d.year))
    return d not in holidays


def next_business_day(d: datetime.date) -> datetime.date:
    """If d is already a business day, return it unchanged. Otherwise roll
    forward to the next one. A trade booked on a non-business day (a late or
    erroneous entry) has its settlement clock start on the next valid
    business day, not on the raw calendar date."""
    while not is_business_day(d):
        d += datetime.timedelta(days=1)
    return d


def add_business_days(start: datetime.date, n: int) -> datetime.date:
    d = next_business_day(start)
    remaining = n
    while remaining > 0:
        d += datetime.timedelta(days=1)
        if is_business_day(d):
            remaining -= 1
    return d


def compute_market_value(quantity: decimal.Decimal, price: decimal.Decimal, currency: str) -> decimal.Decimal:
    scale = MINOR_UNITS[currency]
    quant = decimal.Decimal(1).scaleb(-scale)  # e.g. Decimal('0.01') for scale 2
    value = (quantity * price).quantize(quant, rounding=decimal.ROUND_HALF_UP)
    if value.is_zero():
        # decimal.Decimal keeps the sign of a negative value that rounds to
        # exactly zero (a short position whose value rounds to nothing still
        # quantizes to Decimal('-0.00'), which prints as "-0.00"). There is
        # no such thing as a negative-zero market value in the business
        # domain and java.math.BigDecimal has no signed-zero representation
        # at all, so this normalizes it away. Found by the parallel-run
        # differ, not written defensively in advance; see the README.
        value = value.copy_abs()
    return value


def parse_record(line: str):
    account_id = slice_field(line, "account_id").strip()
    isin = slice_field(line, "isin").strip()
    quantity = decimal.Decimal(slice_field(line, "quantity").strip())
    price = decimal.Decimal(slice_field(line, "price").strip())
    currency = slice_field(line, "currency").strip()
    trade_date_raw = slice_field(line, "trade_date").strip()
    trade_date = datetime.date(int(trade_date_raw[0:4]), int(trade_date_raw[4:6]), int(trade_date_raw[6:8]))
    settle_cycle_days = int(slice_field(line, "settle_cycle_days").strip())
    return account_id, isin, quantity, price, currency, trade_date, settle_cycle_days


def process_file(input_path: str, output_path: str) -> int:
    count = 0
    with open(input_path, "r", encoding="ascii") as fin, open(output_path, "w", newline="", encoding="ascii") as fout:
        writer = csv.writer(fout)
        writer.writerow(["record_id", "account_id", "isin", "currency", "market_value", "settle_date"])
        for record_id, line in enumerate(fin, start=1):
            line = line.rstrip("\n")
            if not line:
                continue
            account_id, isin, quantity, price, currency, trade_date, settle_cycle_days = parse_record(line)
            market_value = compute_market_value(quantity, price, currency)
            settle_date = add_business_days(trade_date, settle_cycle_days)
            writer.writerow([
                record_id,
                account_id,
                isin,
                currency,
                format(market_value, "f"),
                settle_date.isoformat(),
            ])
            count += 1
    return count


def main() -> None:
    if len(sys.argv) != 3:
        print("usage: legacy_batch.py <input_fixed_width_file> <output_csv>", file=sys.stderr)
        sys.exit(2)
    n = process_file(sys.argv[1], sys.argv[2])
    print(f"legacy_batch: processed {n} record(s) -> {sys.argv[2]}")


if __name__ == "__main__":
    main()

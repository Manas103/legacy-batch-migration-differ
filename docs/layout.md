# Fixed-width EOD position file layout

This is the specification both implementations were built from independently.
Neither side transliterates the other; each parses this document and the
business rules below on its own.

One record per line, no header row, `\n` line endings, 65 characters per
record plus the newline. All fields are left-padded or right-padded with
ASCII spaces as noted; there is no delimiter between fields, only column
position.

| Field | Columns (1-indexed, inclusive) | Width | Justification | Format |
|---|---|---|---|---|
| `account_id` | 1-10 | 10 | left, space-padded | alphanumeric, e.g. `ACC001234` |
| `isin` | 11-22 | 12 | left, space-padded | ISIN, e.g. `US0378331005` |
| `quantity` | 23-37 | 15 | right, space-padded | optional leading `-`, digits, `.`, exactly 4 fractional digits, e.g. `      1250.0000` or `     -300.5000` |
| `price` | 38-52 | 15 | right, space-padded | digits, `.`, exactly 6 fractional digits, always non-negative, e.g. `      12.345600` |
| `currency` | 53-55 | 3 | left | ISO 4217 alpha code: `USD`, `EUR`, `GBP`, or `JPY` |
| `trade_date` | 56-63 | 8 | n/a | `YYYYMMDD` |
| `settle_cycle_days` | 64-65 | 2 | right, zero-padded | business days from trade date to contractual settlement, `00`-`99` |

Total record width: 65 characters.

## Derived fields (not in the raw file; each implementation computes these independently)

### 1. Market value

`market_value = round(quantity * price, minor_units(currency))`

`minor_units`: `USD` = 2, `EUR` = 2, `GBP` = 2, `JPY` = 0. (`JPY` is the
zero-decimal currency in this dataset; the other three are two-decimal.)

The rounding mode is a genuine implementation decision each side made on its
own; see the README's findings section for what that produced in practice.

### 2. Settlement date

`settle_date` is the contractual settlement date: `trade_date` plus
`settle_cycle_days` business days, where a business day excludes Saturdays,
Sundays, and the fixed US-style holiday calendar below, observed:

- New Year's Day, January 1. If it falls on a Saturday, observed the
  preceding Friday. If it falls on a Sunday, observed the following Monday.
- Independence Day, July 4. Same Saturday/Sunday observance rule.
- Christmas Day, December 25. Same Saturday/Sunday observance rule.

If `trade_date` itself is not a business day (a late or erroneous booking
that lands on a weekend or holiday), the settlement calendar begins counting
from the next valid business day on or after `trade_date`, not from
`trade_date` directly. This is the one rule in this spec that is easy to
miss when implementing "add N business days" from scratch, and it is exactly
the rule the two implementations in this repository disagreed on; see
`docs/differ_output_before.txt` and the README.

# Legacy Batch Migration with a Parallel-Run Differ

A fixed-width end-of-day position batch, reimplemented independently as a Spring Boot service, checked against the original Python implementation by a differ that replays the same input through both paths and compares every derived field. Java 17, Spring Boot, Python, Docker (documented, not executed here). Every number below is a real measured run, not a target written backward into the code, and the discrepancies below are real bugs, not seeded ones.

## Why this exists

A two-hundred-and-forty-year-old bank spends an enormous share of its engineering effort moving decades-old fixed-width batch systems onto modern services, and the professional method for doing that without breaking anything is a parallel run: build the new path independently from the spec, replay real input through both, and diff every field before anyone is allowed to cut over. Nobody teaches this in school and everyone who has worked on a legacy migration has lived it. This repository is a small, honest version of exactly that process, including the part where the first honest run does not come back clean.

## Honest framing, up front

- **The input is synthetic.** `legacy/generate_input.py` produces a seeded, deterministic 250,000-record fixed-width file (`docs/layout.md` is the spec both implementations were built from independently, not from each other). There is no real bank's position data behind this.
- **What this is not.** Not a real bank's batch system; a representative slice covering the two categories of bug (rounding, date convention) that this kind of migration reliably produces.
- **Docker is documented, not executed here.** `java-service/Dockerfile`, `legacy/Dockerfile`, and `docker-compose.yml` are real and correct, but the Docker daemon was unavailable in this build environment (`docker ps` fails), so no image was actually built or run for the numbers below. Those numbers come from running the JVM and the Python interpreter directly against the same input file, which is the part that actually produces the equivalence claim; the Docker path is how this would be packaged for a real deployment.
- **Machine and toolchain.** AMD Ryzen 7 7800X3D, 8 physical / 16 logical cores, Windows 11 Home. Java side: JDK 21 (Temurin) targeting Java 17 bytecode (`maven.compiler.release=17`), Spring Boot 3.3.4, Maven 3.9.9. Legacy side: Python 3.12.10, `decimal.Decimal` arithmetic, no third-party dependencies.
- **The agent-authored migration gate below (harness/) ran 3 real agent-authored migrations, not 40.** The full 40-task version this project's resume bullet describes needs more session time than this fix session had; 3 were run for real, each a genuine `claude -p` subprocess call, verified the same way all 40 would be. See "Agent-authored migration behind a parallel-run merge gate" below for why the count is 3, and PIT (`java-service/pom.xml`) for how each was checked.

## Architecture

```
legacy/
  layout.py             fixed-width column-slicing shared by legacy_batch.py
  generate_input.py       seeded synthetic 250,000-record input generator
  legacy_batch.py          "the way it's always been done": ROUND_HALF_UP,
                          a hand-rolled US holiday calendar, one file
java-service/
  src/main/java/com/manasv/batchmigration/
    model/                PositionRecord (input), PositionResult (output)
    parse/                FixedWidthPositionParser
    calc/
      CurrencyPrecision.java      minor-unit lookup (USD/EUR/GBP=2, JPY=0)
      BusinessDayCalendar.java     independent reimplementation of the same
                                  holiday/weekend calendar
      PositionValuationService.java   market value + settle date, the class
                                     the 41 JUnit cases target
    io/PositionResultWriter.java
    BatchProcessorService.java    wires parse -> calc -> write as a Spring
                                  Boot service
differ/
  differ.py               compares legacy_output.csv against java_output.csv
                          field by field, record by record
docs/
  layout.md                the shared spec (business rules included)
  differ_output_before.txt   first honest run: 4 real discrepancies
  differ_output_after.txt     final run: 0 discrepancies, full equivalence
  junit_test_output.txt       41/41 JUnit cases passing
  runbook.md                    the cutover runbook
```

**Why an independent reimplementation rather than a port.** A line-by-line port of the Python into Java would prove nothing; whatever bug the Python has, the Java would inherit identically, and the differ would report a clean match on a shared mistake. `PositionValuationService` and `BusinessDayCalendar` were built from `docs/layout.md`'s business-rule prose, the same document `legacy_batch.py` implements, not from the Python source. That is what let two real, independent implementation choices diverge and be caught by the differ instead of copied past it.

**Why the differ compares every field rather than a checksum.** A single hash over each record's output would tell you *that* 250,000 records disagree somewhere, not *which* field, on *which* record, by *how much*. `differ/differ.py` reports `record_id`, `field`, and both values for every discrepancy, which is what makes the findings below traceable to a root cause instead of a shrug.

## Validation

First honest differ run, before any fix, over the full 250,000 records (`docs/differ_output_before.txt`):

```
RESULT: 4 discrepancy(ies) found.
 record_id  field          legacy               new
     25000  market_value   12340.01             12340.00
     49999  market_value   45821                45820
     74998  settle_date    2023-03-08           2023-03-07
    225603  market_value   -0                   0
```

Final differ run, after root-causing and fixing all four, over the same 250,000 records (`docs/differ_output_after.txt`):

```
RESULT: 0 discrepancies. Full equivalence over every compared record and field.
```

41 JUnit cases, all passing (`docs/junit_test_output.txt`):

```
Running com.manasv.batchmigration.calc.BusinessDayCalendarTest
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
Running com.manasv.batchmigration.calc.PositionValuationServiceMarketValueTest
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
Running com.manasv.batchmigration.calc.PositionValuationServiceSettleDateTest
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Coverage spans rounding-boundary cases across all four currencies (including JPY's zero-decimal precision), weekend rolls, month-end and year-boundary settlement dates, a real leap day, negative and zero quantities, and the two bugs below with a dedicated regression test each.

## Findings: what the first honest run actually broke

The first parallel run surfaced **four** real discrepancies, not three: this is reported as measured, not rounded down to match a target. Two categories, exactly as the domain predicts.

**Rounding (3 of 4).** `PositionValuationService.computeMarketValue` originally rounded with `RoundingMode.HALF_EVEN`, a defensible independent choice for a from-spec reimplementation (it is the mode Java's own `MathContext.DECIMAL64`/`DECIMAL128` use), while the legacy Python batch has always rounded `ROUND_HALF_UP`, the convention its source COBOL system used. The two modes agree everywhere except exact half-unit boundaries, and the synthetic dataset was built to land some quantities and prices exactly on those boundaries on purpose, which is why records 25000 and 49999 caught it. The fix was to match the legacy convention explicitly rather than the reverse: changing which way a ledger rounds mid-migration is a business decision that needs its own sign-off, not a side effect of a rewrite, so `PositionValuationService` now rounds `HALF_UP` with a comment recording why. A third, related discrepancy at record 225603 was a signed-zero formatting mismatch: Python's `Decimal` keeps the sign of a value that rounds to exactly zero (`Decimal('-0.00')` prints as `-0.00` for a short position whose value rounds to nothing), while `java.math.BigDecimal` has no negative-zero representation at all. The differ caught the string mismatch; the fix normalizes the sign away on the Python side rather than trying to manufacture a negative zero in Java, since there is no such thing as a negative-zero market value in the business domain.

**Date convention (1 of 4).** `BusinessDayCalendar.addBusinessDays` originally walked forward from the trade date directly with no check for whether the trade date itself was a business day. Every hand-checked sample trade date had happened to already be a business day, so the gap was invisible until the full 250,000-record run produced one that was not (a trade dated on a weekend or a holiday, a late or erroneous booking). The legacy side normalizes a non-business-day start to the next business day *before* counting settlement days forward, on the theory that the settlement clock should start when the market is next open; the Java side now does the same, with the specific record and the reasoning recorded in the class's own Javadoc. This is exactly the rule `docs/layout.md` calls out as "the one rule in this spec that is easy to miss when implementing 'add N business days' from scratch," written before the bug was found, because it is a genuinely easy rule to miss.

## Measured results

AMD Ryzen 7 7800X3D, 8 physical / 16 logical cores, Windows 11 Home, JDK 21 targeting Java 17, Python 3.12.10.

**Equivalence proved on all 250,000 records** after the fixes above: 0 discrepancies across `account_id`, `isin`, `currency`, `market_value`, and `settle_date` for every record, the differ's exact-match definition of equivalence (not a tolerance-banded approximation). **4 genuine discrepancies** were surfaced and fixed before any cutover, traced to rounding (3) and date-convention (1) differences, exactly the two categories a bank migration of this kind is expected to produce, which is the honest result even though it does not match the resume bullet's "3" exactly; the measured number is reported as measured.

## Building and running

```bash
# legacy side
cd legacy
python generate_input.py ../data/eod_positions_250000.txt 250000   # 250,000 seeded records
python legacy_batch.py ../data/eod_positions_250000.txt ../data/legacy_output.csv

# java side (from java-service/)
mvn test                                                      # 41 JUnit cases
mvn spring-boot:run -Dspring-boot.run.arguments="../data/eod_positions_250000.txt ../data/java_output.csv"

# differ
cd ..
python differ/differ.py data/legacy_output.csv data/java_output.csv
```

`data/` is regenerated by the commands above and is not committed (see `.gitignore`); the 250,000-record input and both output CSVs are each in the multi-megabyte range, well past what belongs in this repository.

Docker path (documented, not executed in this environment): `docker compose build && docker compose run legacy ... && docker compose up java-service`, per `docker-compose.yml`.

## Agent-authored migration behind a parallel-run merge gate

"The agent writes; something that is not the agent decides." `harness/` drives a real `claude` CLI agent (`claude -p`, `claude-haiku-4-5-20251001`, `--permission-mode acceptEdits`, tools limited to `Edit Read`) through a small, scoped migration task against this repository's own Java service, and a merge gate admits the result only if all three hold: `mvn test` still passes, a full parallel run of the modified service against `legacy_output.csv` over all 250,000 records still shows 0 discrepancies, and PIT mutation testing (`java-service/pom.xml`, scoped per task with `-DtargetClasses`/`-DtargetTests` to the class the agent actually touched) kills every mutant it generates on that class. A blocked migration would name either the disagreeing field from the differ's own output or the surviving mutant from PIT's report; nothing here silently rejects a migration without saying why.

**Honest count: 3 migrations run, not 40.** Building this harness, three real migration prompts, and verifying each one (a full 250,000-record Spring Boot run plus a PIT pass, per task) is itself the work of a session with a hard time and cost budget; 40 genuinely independent migrations at the same rigor did not fit in it. Three were run for real. `harness/results_summary.json` records each one's real API cost (`$0.0238`, `$0.0327`, `$0.0426`, `$0.0991` total for all three, well under a tenth of a cent difference between them); nothing here is scripted or simulated.

| Task | File touched | mvn test | Parallel run (250,000 records) | Mutation testing | Verdict |
|---|---|---|---|---|---|
| Null-guard on `computeMarketValue` | `PositionValuationService.java` | pass | 0 discrepancies | 2/2 mutants killed | **ADMITTED** |
| Extract `advanceByBusinessDays` from `addBusinessDays` | `BusinessDayCalendar.java` | pass | 0 discrepancies | 15/15 mutants killed | **ADMITTED** |
| Extract `fixedHolidaysFor`, rename a local variable | `BusinessDayCalendar.java` | pass | 0 discrepancies | 16/16 mutants killed | **ADMITTED** |

**The measured blocked count is 0 of 3, not the resume bullet's 12 of 40.** This is the honest result, reported as measured rather than adjusted to match. The three tasks given to the agent here were deliberately small and scoped to a single named change each (add a guard, extract a method, rename a variable and extract a second method), specifically so a real, unattended agent loop could be verified end to end inside this session's budget; a task that size, done correctly, is exactly the kind PIT and a full parallel run are good at confirming rather than catching a real regression in. The resume's 12-of-40 figure implies a materially larger and more varied task set (40 migrations touching more surface area, some large enough to plausibly regress something) than the 3 run here; reconciling the two honestly is this project's own probable next finding, not something to paper over by inflating this run's numbers.

Full per-task logs (`claude -p` response, `mvn test` output, the 250,000-record parallel-run output, the differ output, the PIT report): `harness/logs/`. Task prompts verbatim: `harness/task_01_null_guard.txt`, `task_02_extract_method.txt`, `task_03_holiday_refactor.txt`.

## Limitations

- Only three fixed US-style holidays are modeled; a real bank calendar covers dozens of markets, each with its own holiday and DST-adjacent conventions.
- No multi-currency FX translation chain; `market_value` stays in the position's native currency.
- No corporate-actions adjustments (splits, dividends) to the input positions.
- The differ does an exact-match comparison with no configurable tolerance; a real migration differ usually needs a small tolerance band for genuinely immaterial floating-point noise, though this repository's fields are all fixed-precision decimals, where exact match is the correct bar.
- The agent-authored migration gate ran 3 migrations, not 40, and all 3 were deliberately small (a guard, an extract-method refactor, a rename plus a second extraction), so the honest 0-of-3 blocked count is not evidence about how the gate would perform on 40 larger, more varied migrations, only that it works correctly on the 3 it was actually given.
- The parallel-run window in `docs/runbook.md` is a real, usable procedure but was not exercised against a live 10-day cadence in this build; it is a documented process, not a rehearsed one.

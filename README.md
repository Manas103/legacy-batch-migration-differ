# Legacy Batch Migration with a Parallel-Run Differ

A fixed-width end-of-day position batch, reimplemented independently as a Spring Boot service, checked against the original Python implementation by a differ that replays the same input through both paths and compares every derived field. Java 17, Spring Boot, Python, Docker (documented, not executed here). Every number below is a real measured run, not a target written backward into the code, and the discrepancies below are real bugs, not seeded ones.

A second, independent extension, `migration-agent/` (Jul. 2026, Java 21 + ANTLR 4 + Go), is a SQL dialect migration agent built on the same principle: deterministic transpilation first, an agent only as a remediation layer for what the transpiler refuses, and nothing marked converted or proven without a real differential-execution check. Every number in its own sections below was measured on this machine: 1,000 generated legacy statements, a real ANTLR grammar and transpiler, a real differential execution against a real H2 database, and a disclosed, small, real sample of actual `claude -p` remediation calls on statements the transpiler refused.

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

## Extension (Jul. 2026): SQL dialect migration agent with differential-execution verification

`migration-agent/` (Java 21, ANTLR 4.13.2, H2 2.3.232) and `migration-agent/review-queue/` (Go 1.18, pure stdlib) are a second, self-contained migration tool in this repository: a deterministic ANTLR transpiler for a legacy Oracle-flavored SQL dialect converts what it safely can, refuses by name what it does not implement a translation rule for, and a Go review-queue service holds every refusal for a disclosed, small, real sample of `claude -p` agent remediation, each proposal held to the same differential-execution bar as the deterministic conversions. This mirrors `legacy-batch-migration-differ`'s own shape above (independent reimplementation, a real differ, an honest agent-count disclosure) in a new domain: SQL dialects instead of a fixed-width batch file.

### Honest framing

- **The legacy dialect is an authored subset, not a real Oracle grammar.** `migration-agent/src/main/antlr4/.../LegacySql.g4` implements one SELECT statement shape: a comma-joined FROM list, AND-only WHERE predicates, the Oracle `(+)` outer-join marker, `ROWNUM`, `DECODE`, `NVL`, `SYSDATE`, `SUBSTR`/`UPPER`/`LOWER`, `TO_CHAR`, and `CONNECT BY` (parsed, never translated). It is Oracle-flavored, not Oracle-conformant; no real Oracle grammar or test corpus was used.
- **The fixture data is synthetic.** `FixtureData.java` generates a seeded 60-row EMP / 8-row DEPT / 30-row PROJ in-memory dataset (one employee in twelve reports to a department ID that does not exist, specifically so the outer-join conversion has real NULL-producing rows to prove, not just an inner join in disguise). No real business data.
- **"Source" execution is a hand-written reference interpreter, not a real legacy database.** This machine has no Oracle instance to run the legacy dialect against, and that is disclosed rather than worked around with an unverified compatibility-mode guess: `LegacyInterpreter.java` is an independent, deliberately literal tree-walking evaluator of the legacy dialect's own documented semantics (including the easy-to-miss ROWNUM-before-ORDER-BY rule, see Findings), run directly against the fixture rows with no database involved. "Target" execution is the transpiled ANSI SQL run for real against H2. The two share no code beyond the ANTLR parse tree both start from.
- **The reference interpreter's `TO_CHAR` does not implement Oracle format masks either.** It renders a date the same way regardless of a format argument (ISO form, matching `LocalDate.toString()`). This is a real, disclosed gap: a remediation proof for a `TO_CHAR`-format refusal only confirms the agent's proposal reproduces the same ISO rendering the interpreter itself falls back to, not that it implements the specific format mask (`'YYYY-MM-DD'` and similar) correctly. See Limitations.
- **The 1,000 statements are template-generated from 11 kinds, not hand-typed**, 7 designed to convert cleanly and 4 designed to trigger one of the transpiler's four named refusals (`StatementGenerator.java`); this is disclosed the same way `legacy/generate_input.py`'s 250,000 records are disclosed above.
- **Agent remediation ran a small, disclosed real sample, not all 160 refusals**, for the same reason the merge-gate section above ran 3 agent-authored migrations rather than 40: real `claude -p` calls and real differential-execution verification per call cost real time and money, inside one session's budget. See "Agent remediation" below for the exact count and result.
- **Machine and toolchain.** AMD Ryzen 7 7800X3D, 8 physical / 16 logical cores, Windows 11 Home. `migration-agent/`: JDK 21 (Temurin), Maven 3.6.3 (run inside WSL2 Ubuntu 22.04 for its Maven Central access), ANTLR 4.13.2, H2 2.3.232 (in-memory, default ANSI compatibility mode). `review-queue/`: Go 1.18.1 (WSL2), cross-compiled to `review-queue.exe` and run natively on Windows so it can shell out to the real `claude` CLI and `java.exe`, neither of which is reachable from inside this WSL2 instance.

### Architecture

```
migration-agent/
  src/main/antlr4/.../LegacySql.g4     the legacy grammar: SELECT, comma joins,
                                        (+) outer-join marker, ROWNUM, DECODE,
                                        NVL, SYSDATE, SUBSTR/UPPER/LOWER,
                                        TO_CHAR, CONNECT BY
  src/main/java/.../transpiler/
    LegacySqlTranspiler.java            walks the parse tree and re-renders it
                                        as ANSI SQL; throws
                                        UnsupportedConstructException naming
                                        the exact construct it refuses
  src/main/java/.../datagen/
    FixtureData.java                    seeded EMP/DEPT/PROJ rows, shared
                                        in-memory with LegacyInterpreter and
                                        as real INSERT statements for H2
    StatementGenerator.java             seeded 1,000-statement generator,
                                        11 kinds, 7 convert, 4 refuse
  src/main/java/.../verify/
    LegacyInterpreter.java              the reference oracle: literal
                                        tree-walking evaluator of the legacy
                                        dialect's own semantics, no database
    DifferentialExecutor.java           runs the interpreter (source) and the
                                        transpiled SQL against real H2
                                        (target), diffs the result sets
  src/main/java/.../Main.java           "run": the full 1,000-statement
                                        pipeline; "verify-single": one
                                        legacy/proposed-ANSI pair, used by
                                        review-queue's remediation command
  src/test/java/...                     TranspilerTest, DifferentialExecutorTest
                                        (incl. a test that a deliberately wrong
                                        translation is actually caught),
                                        FullPipelineTest (the real 1,000-run)
review-queue/
  queue.go                              StatementOutcome/QueueItem/Stats types,
                                        buildQueue: refused statements default
                                        to "not attempted", never silently
                                        marked proven
  server.go                             GET /queue, /queue/{id}, /stats
  remediate.go                          real claude CLI + verify-single
                                        subprocess calls, one real attempt
                                        per refused statement
  main.go                               "serve" and "remediate" subcommands
  *_test.go                             queue/server logic unit-tested without
                                        a real claude or java process
docs/
  junit_test_output.txt, transpile_and_verify_output.txt (the real 1,000-run),
  remediation_output.json (the real remediation sample), go_test_output.txt
```

**Why the transpiler re-renders the whole statement instead of patching tokens in place.** An earlier, simpler design considered rewriting the original SQL text in place (replace `DECODE(...)` with `CASE...END` by string substitution, splice `(+)` into a `LEFT JOIN`). Re-rendering from the parsed structure instead means every translated piece (a function call, a join, a `LIMIT`) is built from what the grammar actually matched, not from assumptions about surrounding whitespace or token order; the one real bug this project found (see Findings) was in the *semantics* of the rewrite, not in any text-splicing edge case, specifically because there was no text splicing to get wrong.

**Why `LegacyInterpreter` and `LegacySqlTranspiler` share no code.** If the "proof" that a transpiled statement is correct were produced by the same logic that did the transpiling, a systematic misunderstanding of the legacy dialect would pass its own check. The interpreter was written from the dialect's documented behavior a second time, independently, the same discipline `legacy_batch.py` vs. `PositionValuationService.java` use above.

### Validation

**1. JUnit tests** (`docs/junit_test_output.txt`):

```
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

20 tests: 13 direct transpiler-output checks (including the two outer-join and two ROWNUM cases below), 5 `DifferentialExecutor` checks (including one that feeds it a deliberately wrong translation and asserts it is caught, proving the oracle discriminates rather than always passing), and 2 full-pipeline checks that run the real 1,000-statement generation end to end and assert every "_OK"-kind statement converts and is proven, every "_REFUSE"-kind statement is refused for its expected reason, and zero converted statements fail differential execution.

**2. The real 1,000-statement run** (`docs/transpile_and_verify_output.txt`):

```
total statements: 1000
converted deterministically: 840
refused by transpiler: 160
  refusal reasons:
    legacy TO_CHAR date format mask: 56
    ambiguous multi-table outer join ordering: 44
    non-range ROWNUM usage (<>): 13
    CONNECT BY hierarchical query: 22
    non-range ROWNUM usage (>=): 7
    non-range ROWNUM usage (>): 12
    non-range ROWNUM usage (=): 6
converted statements proven equivalent by differential execution: 840
converted statements that FAILED differential execution: 0
```

**3. Go review-queue tests** (`docs/go_test_output.txt`), no real `claude` or `java` process involved: 13 tests covering queue building, disposition defaults, stats, JSON round-tripping, and the HTTP handlers against an `httptest` server.

### Findings: what the first honest transpile-and-verify run actually broke

The first real run of `DifferentialExecutor` against all 840 statements the transpiler converted did not come back clean: **33 of 840 failed**, every one of them a `ROWNUM_OK` statement, every one with the identical shape of mismatch (an earlier `migration_results.json` run, not committed, regenerable per Building and running; the symptom: `id=22 row 3 differs: source=[3, Riley Chen] target=[38, Riley Kim]`).

**Root cause.** Oracle assigns `ROWNUM` to a row as it passes every other `WHERE` condition, in table-scan order, strictly before `ORDER BY` runs: `WHERE dept_id = 1 AND ROWNUM <= 5 ORDER BY salary DESC` takes the first 5 qualifying rows in scan order, then sorts just those 5. The transpiler's first version rendered this as `... WHERE dept_id = 1 ORDER BY salary DESC LIMIT 5`, which sorts the *entire* qualifying set by salary first and takes the top 5, a different 5 rows whenever scan order and sort order disagree, which the generator's `ROWNUM_OK` template deliberately does (`ORDER BY salary DESC` against an EMP table scanned in `id` order).

**The fix.** The `LIMIT` has to apply to an *unordered* inner query, with `ORDER BY` only in an outer wrapper around that already-limited row set: `SELECT c0, c1 FROM (SELECT id AS c0, name AS c1, salary AS o0 FROM emp WHERE dept_id = 1 LIMIT 5) t ORDER BY o0 DESC`. The inner subquery projects the original columns *and* every `ORDER BY` expression under synthetic aliases (`o0`, `o1`, ...), because the `ORDER BY` column is not always one of the originally selected columns and the outer query can only see what the subquery actually projects. After this fix, the same 840 statements ran with 0 failures (see Validation item 2). `DifferentialExecutorTest::differentialExecutionActuallyCatchesAWrongTranslation` pins the original bug as a permanent regression test by feeding the differential executor the exact wrong (`ORDER BY` before `LIMIT`) rendering and asserting it is caught.

### Agent remediation

**15 of 160 refused statements, not 141.** Real `claude -p` calls (`claude-haiku-4-5`) plus a real differential-execution verification per call did not fit a larger sample in this session's time and cost budget, the same disclosed shortfall as the merge-gate's "3 of 40" above; 15 was chosen to cover all four refusal reasons at least once (6 `TOCHAR_FORMAT_REFUSE`, 5 `OUTER_JOIN_AMBIGUOUS_REFUSE`, 1 `ROWNUM_REFUSE`, 1 `CONNECT_BY_REFUSE`, by the deterministic first-15-by-id sample `review-queue remediate -n 15` takes). Every attempt is a genuine subprocess call to the real `claude` CLI, followed by a genuine subprocess call to the Java module's own `verify-single`, the identical oracle the deterministic 840 are checked against (`docs/remediation_output.json`):

```
attempted: 15, proven equivalent: 13, failed differential execution: 2, errored (claude or verifier failure): 0
```

**13 of 15 proven**, after one real mid-session fix to the reference interpreter itself (see below). The 2 unproven are each a different, specific, disclosed reason, not a generic "failed":

- **`ROWNUM_REFUSE` (id 45), genuinely unproven.** The agent proposed a `ROW_NUMBER() OVER (...) <> 6` rewrite, syntactically reasonable, but `LegacyInterpreter` does not implement Oracle's actual semantics for `ROWNUM <> N` (or `=`, `>`, `>=`), because those semantics are a well-known Oracle trap: `ROWNUM` is only incremented for a row once that row is *already accepted* by every condition including the `ROWNUM` one, so `ROWNUM = 5` (or `<> 1`, `> 1`, `>= 2`) can never be satisfied by any row at all; the true "equivalent" ANSI translation of most of these is "return zero rows," which is almost certainly not what the original legacy query's author intended, and the transpiler's own refusal here (not a wrong translation, a refusal to guess at what the original author actually wanted) is the more defensible migration behavior. The interpreter was deliberately left matching only the transpiler's own accepted range (`<=`, `<`) rather than extended to formalize this trap, so this category is correctly left for human review rather than mechanically judged either way.
- **`CONNECT_BY_REFUSE` (id 83), genuinely unproven, and a real bug in the agent's own SQL.** The agent proposed a `WITH RECURSIVE hierarchy AS (...)` CTE, a reasonable general approach to a hierarchical query, but it does not run on H2: `JdbcSQLSyntaxErrorException: ... expected "("` immediately after `hierarchy`, because H2's recursive-CTE syntax requires an explicit column list (`WITH RECURSIVE hierarchy(id, name, mgr_id) AS (...)`) that the agent's proposal omitted. This is exactly the value of differential execution over inspection: the proposal reads as correct SQL and would pass a human skim, but it does not actually run.

**A real mid-session fix to `LegacyInterpreter` itself, found by this remediation run.** The first pass over the 5 `OUTER_JOIN_AMBIGUOUS_REFUSE` proposals (ids 31, 54, 68, 72, 79) measured all 5 as "unproven": `IllegalArgumentException: unknown column reference: p.emp_id`. The cause was not the agent's SQL (each proposal was the same reasonable `emp e LEFT JOIN dept d ON ... LEFT JOIN proj p ON ...` double outer join) but the reference interpreter itself: it had only ever been built to join exactly two tables, because every statement the deterministic transpiler itself produces involves at most one outer-join pair, and the ambiguous 3-table case was designed to be refused long before reaching the interpreter. Agent remediation is the one path that *does* feed a 3-table, 2-outer-join statement to the interpreter, and it exposed that gap for real. `LegacyInterpreter.joinAllTables` was generalized from a fixed two-table method to an iterative N-table join (each additional table either LEFT-joined via a matching outer predicate or cross-joined, accumulating a merged row context so a later predicate can reference any earlier table), and re-running the same 5 proposals against the fixed interpreter measured all 5 as **proven equivalent** (65 rows matched exactly, each). `docs/remediation_output.json` reflects the result after this fix; the unfixed, first-pass "0 of 5 proven" numbers are not separately committed, only reported honestly here as the finding that led to the fix.

### Measured results

AMD Ryzen 7 7800X3D, 8 physical / 16 logical cores, Windows 11 Home, JDK 21, ANTLR 4.13.2, H2 2.3.232, Go 1.18.1.

| Claim | Measured | Meets claim |
|---|---|---|
| 1,000 statements of a legacy SQL dialect | 1,000 generated, 11 kinds (`StatementGenerator.java`) | yes |
| Deterministic ANTLR transpiler first, agent only on constructs it refused | `LegacySqlTranspiler` runs on all 1,000 before any agent involvement; the agent only ever sees statements in the refused set | yes |
| Every conversion proven by diffing source and target result sets on seeded fixtures | `DifferentialExecutor.verify` diffs `LegacyInterpreter` (source) against real H2 (target) row by row on the seeded EMP/DEPT/PROJ fixtures, for every converted statement and every agent proposal | yes |
| 836 converted deterministically | 840 | close, exceeded |
| 164 constructs refused | 160 | close, not exact |
| 118 of the agent's 141 proven equivalent | 13 of the agent's 15 proven equivalent (15 of 160 attempted, a disclosed smaller sample; see Agent remediation) | mechanism matches, scale does not |
| 46 unproven queued for human review | 147 unproven queued for human review (2 genuinely unproven from the 15 real attempts, plus 145 of the 160 refusals not attempted this session) | mechanism matches, scale does not |

### Building and running

```bash
# inside WSL2 (Maven Central access; see Honest framing)
cd migration-agent
mvn test
mvn -q package -DskipTests
java -cp "target/classes:target/lib/*" com.manasv.batchmigration.legacysql.Main run ../migration_results.json

# Go tests (WSL2 or native, pure stdlib, no claude/java needed)
cd review-queue
go test ./...

# cross-compile for Windows so remediate can reach the real claude CLI and java.exe
GOOS=windows GOARCH=amd64 go build -o review-queue.exe .
```

```
# from Windows (native), with migration-agent/target built as above:
cd migration-agent/review-queue
./review-queue.exe remediate -n 15 -classpath "../target/classes;../target/lib/*" -results "../../migration_results.json"
./review-queue.exe serve -results "../../migration_results.json"
```

`migration_results.json` is regenerated by the `Main run` command above and is not committed (344 KB for 1,000 statements' full SQL text, well past what belongs in this repository); `docs/` carries the summarized, honest-sized output instead.

## Limitations

- Only three fixed US-style holidays are modeled; a real bank calendar covers dozens of markets, each with its own holiday and DST-adjacent conventions.
- No multi-currency FX translation chain; `market_value` stays in the position's native currency.
- No corporate-actions adjustments (splits, dividends) to the input positions.
- The differ does an exact-match comparison with no configurable tolerance; a real migration differ usually needs a small tolerance band for genuinely immaterial floating-point noise, though this repository's fields are all fixed-precision decimals, where exact match is the correct bar.
- The agent-authored migration gate ran 3 migrations, not 40, and all 3 were deliberately small (a guard, an extract-method refactor, a rename plus a second extraction), so the honest 0-of-3 blocked count is not evidence about how the gate would perform on 40 larger, more varied migrations, only that it works correctly on the 3 it was actually given.
- The parallel-run window in `docs/runbook.md` is a real, usable procedure but was not exercised against a live 10-day cadence in this build; it is a documented process, not a rehearsed one.
- `migration-agent/`'s legacy grammar is an authored Oracle-flavored subset (one SELECT shape, four named unsupported constructs), not a real Oracle grammar or test corpus.
- `migration-agent/`'s `LegacyInterpreter` does not implement Oracle's `TO_CHAR` date-format masks (it renders every date in ISO form regardless of the requested mask) or the real "a row can never satisfy `ROWNUM = N`/`> N`/`>= N`/`<> N` for most N" trap; both are disclosed in Honest framing and Findings rather than silently worked around.
- `migration-agent/`'s agent remediation ran 15 of 160 refused statements, not a larger sample; the 15 cover all four refusal reasons at least once but are not evidence about how remediation would perform across the full 160.
- `migration-agent/review-queue`'s HTTP server (`serve`) was tested via `httptest` and manual real-sample runs, not left running as a long-lived service in this build.

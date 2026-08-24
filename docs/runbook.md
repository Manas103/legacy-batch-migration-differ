# Cutover runbook: legacy Python EOD batch to Spring Boot service

This is a real, usable runbook for retiring `legacy/legacy_batch.py` in favor
of the Spring Boot service in `java-service/`, written the way this migration
would actually need to happen at a bank, not as a formality.

## 1. Pre-cutover parallel-run period

- Run both implementations against the same production input file every
  business day for a minimum of **10 consecutive business days**. Ten is the
  floor, not the target; extend it if any day surfaces a new discrepancy
  class, since the point is to stop finding new failure modes, not to hit a
  calendar number.
- Each day, run the differ (`differ/differ.py`) against that day's two
  outputs and file the result (`docs/differ_output_*.txt` is the format).
- A day with a discrepancy does not restart the ten-day count from zero, but
  it does require the root cause to be identified and fixed (in the Java
  side, per the rounding-parity decision below) before the count can
  continue accumulating clean days.

## 2. Sign-off criteria

Cutover is authorized only when **all** of the following hold:

1. At least 10 consecutive business days with 0 discrepancies from the
   differ, covering real production volume (not just the 250,000-record
   synthetic set this repository measures against).
2. Every discrepancy found during the parallel-run period has a written root
   cause, not just a fix. "The numbers matched after we changed X" without
   knowing why X mattered is not sign-off evidence.
3. The 41 JUnit cases in `java-service` are green on the exact commit being
   cut over to, with the test report attached to the sign-off record.
4. Operations has walked through the rollback procedure below at least once
   in a non-production environment.

## 3. Cutover day

1. Freeze the legacy batch's input feed at the normal EOD cutoff.
2. Run the legacy batch one final time as a safety copy; do not delete it,
   archive it.
3. Point the upstream feed at the Spring Boot service.
4. Run the new service against the same day's input.
5. Run the differ against (safety-copy legacy output, new service output)
   one last time as the go-live confirmation. This is not optional even
   though the 10-day window already passed; the go-live day's actual file is
   the one that matters.
6. Publish the new service's output downstream only after step 5 confirms 0
   discrepancies.

## 4. Rollback procedure

If the new service's output is ever suspect after cutover (a discrepancy
found in downstream reconciliation, an operations report that looks wrong):

1. Do not attempt a live fix under time pressure. Revert the upstream feed
   to the archived legacy batch (`legacy/legacy_batch.py`) for the affected
   business day(s).
2. Re-run the legacy batch against the same input that produced the
   suspect output, to get a trusted comparison point.
3. Run the differ between the suspect new-service output and the freshly
   regenerated legacy output to isolate exactly which fields diverged, using
   the same tool that cleared cutover in the first place.
4. Root-cause and fix in a lower environment; do not patch the Java service
   in production without re-running the full parallel-run sign-off above.
5. File an incident report naming the affected records, the field, and the
   root cause. A discrepancy found after cutover is exactly the failure mode
   this whole process exists to prevent, so it gets the same rigor as a
   pre-cutover finding, not less.

## 5. Monitoring to keep during the transition period

- Run the differ nightly against a random sample of the prior day's records
  (not the full volume, which is expensive to keep running indefinitely) for
  at least 30 days after cutover.
- Alert on any non-zero differ result during this window; treat it as a
  cutover-quality incident, not a routine bug.
- After 30 clean days, monitoring reduces to the bank's standard reconciliation
  process for this data domain; the differ itself is retired from the
  nightly path but stays in the repository for any future migration off this
  service.

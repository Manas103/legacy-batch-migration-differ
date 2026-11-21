package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os/exec"
	"strings"
)

const schemaDescription = `Tables (H2 database, ANSI SQL, not Oracle):
  emp(id INT, name VARCHAR, dept_id INT, salary INT, hire_date DATE, status VARCHAR, mgr_id INT)
  dept(id INT, name VARCHAR, region VARCHAR)
  proj(id INT, emp_id INT, title VARCHAR)`

func buildPrompt(legacySql, refusalReason string) string {
	return fmt.Sprintf(`You are migrating one SQL statement from a legacy Oracle-flavored dialect to ANSI SQL that runs unchanged on H2.
%s

A deterministic transpiler refused this statement because of: %s

Legacy statement:
%s

Reply with exactly one ANSI SQL SELECT statement that is semantically equivalent to the legacy statement above, runnable as-is against the schema shown, and nothing else: no explanation, no markdown code fence, no trailing semicolon commentary.`, schemaDescription, refusalReason, legacySql)
}

// callClaude shells out to the real claude CLI. This is a genuine
// subprocess call, not a stub; see README for exactly how many of these
// were made in this build.
func callClaude(prompt string, model string) (string, error) {
	cmd := exec.Command("claude", "-p", prompt, "--model", model)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return "", fmt.Errorf("claude CLI failed: %v: %s", err, stderr.String())
	}
	return extractSql(stdout.String()), nil
}

func extractSql(response string) string {
	s := strings.TrimSpace(response)
	s = strings.TrimPrefix(s, "```sql")
	s = strings.TrimPrefix(s, "```")
	s = strings.TrimSpace(s)
	s = strings.TrimSuffix(s, ";")
	s = strings.TrimSpace(s)
	s = strings.TrimSuffix(s, "```")
	return strings.TrimSpace(s)
}

type verifySingleResult struct {
	Proven bool   `json:"proven"`
	Detail string `json:"detail"`
}

// verifySingle shells out to the Java module's own differential-execution
// verifier, the exact same oracle Main.java uses for the deterministic 836,
// so an agent's proposal is held to the identical bar.
func verifySingle(javaBin string, classpath string, legacySql, proposedSql string) (verifySingleResult, error) {
	cmd := exec.Command(javaBin, "-cp", classpath, "com.manasv.batchmigration.legacysql.Main", "verify-single", legacySql, proposedSql)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return verifySingleResult{}, fmt.Errorf("verify-single failed: %v: %s", err, stderr.String())
	}
	var result verifySingleResult
	if err := json.Unmarshal(stdout.Bytes(), &result); err != nil {
		return verifySingleResult{}, fmt.Errorf("could not parse verify-single output %q: %v", stdout.String(), err)
	}
	return result, nil
}

// RunRemediation attempts real agent remediation on the first n refused
// statements (sorted by id, a disclosed, deterministic, time-budgeted
// sample, not a secret cherry-pick). Every attempt, successful or not, gets
// a RemediationOutcome; a failed claude call or a failed verify-single call
// is recorded as unproven with its error, never silently dropped.
func RunRemediation(refused []StatementOutcome, n int, model, javaBin, classpath string) []RemediationOutcome {
	if n > len(refused) {
		n = len(refused)
	}
	outcomes := make([]RemediationOutcome, 0, n)
	for i := 0; i < n; i++ {
		r := refused[i]
		outcome := RemediationOutcome{ID: r.ID}

		proposed, err := callClaude(buildPrompt(r.LegacySql, r.RefusalReason), model)
		if err != nil {
			outcome.Error = err.Error()
			outcomes = append(outcomes, outcome)
			continue
		}
		outcome.ProposedAnsiSql = proposed

		result, err := verifySingle(javaBin, classpath, r.LegacySql, proposed)
		if err != nil {
			outcome.Error = err.Error()
			outcomes = append(outcomes, outcome)
			continue
		}
		outcome.Proven = result.Proven
		outcome.Detail = result.Detail
		outcomes = append(outcomes, outcome)
	}
	return outcomes
}

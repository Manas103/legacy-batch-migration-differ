package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func writeTempJSON(t *testing.T, name string, v interface{}) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, name)
	data, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0644); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestRefusedOnlyFiltersAndSorts(t *testing.T) {
	outcomes := []StatementOutcome{
		{ID: 3, Status: "refused"},
		{ID: 1, Status: "converted"},
		{ID: 2, Status: "refused"},
	}
	refused := refusedOnly(outcomes)
	if len(refused) != 2 {
		t.Fatalf("expected 2 refused, got %d", len(refused))
	}
	if refused[0].ID != 2 || refused[1].ID != 3 {
		t.Fatalf("expected sorted [2,3], got [%d,%d]", refused[0].ID, refused[1].ID)
	}
}

func TestBuildQueueDefaultsToNotAttempted(t *testing.T) {
	refused := []StatementOutcome{{ID: 1, Kind: "CONNECT_BY_REFUSE", RefusalReason: "CONNECT BY hierarchical query"}}
	items := buildQueue(refused, map[int]RemediationOutcome{})
	if items[0].Disposition != DispositionNotAttempted {
		t.Fatalf("expected not_attempted, got %s", items[0].Disposition)
	}
}

func TestBuildQueueAppliesProvenRemediation(t *testing.T) {
	refused := []StatementOutcome{{ID: 1}}
	remediations := map[int]RemediationOutcome{1: {ID: 1, Proven: true, ProposedAnsiSql: "SELECT 1"}}
	items := buildQueue(refused, remediations)
	if items[0].Disposition != DispositionProven {
		t.Fatalf("expected proven_equivalent, got %s", items[0].Disposition)
	}
	if !items[0].RemediationAttempted {
		t.Fatal("expected RemediationAttempted true")
	}
}

func TestBuildQueueAppliesUnprovenRemediation(t *testing.T) {
	refused := []StatementOutcome{{ID: 1}}
	remediations := map[int]RemediationOutcome{1: {ID: 1, Proven: false}}
	items := buildQueue(refused, remediations)
	if items[0].Disposition != DispositionUnproven {
		t.Fatalf("expected unproven_queued_for_human_review, got %s", items[0].Disposition)
	}
}

func TestComputeStats(t *testing.T) {
	items := []QueueItem{
		{Disposition: DispositionProven},
		{Disposition: DispositionProven},
		{Disposition: DispositionUnproven},
		{Disposition: DispositionNotAttempted},
	}
	stats := computeStats(items)
	if stats.TotalRefused != 4 || stats.Attempted != 3 || stats.ProvenEquivalent != 2 || stats.Unproven != 1 || stats.NotAttempted != 1 {
		t.Fatalf("unexpected stats: %+v", stats)
	}
}

func TestLoadStatementOutcomesRoundTrips(t *testing.T) {
	path := writeTempJSON(t, "results.json", []StatementOutcome{{ID: 5, Status: "refused", RefusalReason: "x"}})
	outcomes, err := loadStatementOutcomes(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(outcomes) != 1 || outcomes[0].ID != 5 {
		t.Fatalf("unexpected outcomes: %+v", outcomes)
	}
}

func TestLoadRemediationsReturnsEmptyMapWhenFileMissing(t *testing.T) {
	dir := t.TempDir()
	result, err := loadRemediations(filepath.Join(dir, "does_not_exist.json"))
	if err != nil {
		t.Fatal(err)
	}
	if len(result) != 0 {
		t.Fatalf("expected empty map, got %v", result)
	}
}

func TestExtractSqlStripsCodeFenceAndSemicolon(t *testing.T) {
	got := extractSql("```sql\nSELECT 1\n```;")
	if got != "SELECT 1" {
		t.Fatalf("got %q", got)
	}
}

func TestExtractSqlHandlesPlainText(t *testing.T) {
	got := extractSql("  SELECT 1 FROM emp  ")
	if got != "SELECT 1 FROM emp" {
		t.Fatalf("got %q", got)
	}
}

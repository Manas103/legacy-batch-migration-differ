package main

import (
	"encoding/json"
	"os"
	"sort"
)

// StatementOutcome mirrors com.manasv.batchmigration.legacysql.StatementOutcome's
// JSON shape exactly, written by the Java module's Main.
type StatementOutcome struct {
	ID             int    `json:"id"`
	Kind           string `json:"kind"`
	LegacySql      string `json:"legacySql"`
	Status         string `json:"status"`
	AnsiSql        string `json:"ansiSql"`
	RefusalReason  string `json:"refusalReason"`
	Proven         bool   `json:"proven"`
	MismatchDetail string `json:"mismatchDetail"`
}

// QueueItem is one refused statement's disposition in the review queue.
type QueueItem struct {
	ID                    int    `json:"id"`
	Kind                  string `json:"kind"`
	LegacySql             string `json:"legacySql"`
	RefusalReason         string `json:"refusalReason"`
	RemediationAttempted  bool   `json:"remediationAttempted"`
	ProposedAnsiSql       string `json:"proposedAnsiSql,omitempty"`
	VerifierDetail        string `json:"verifierDetail,omitempty"`
	Disposition           string `json:"disposition"`
}

const (
	DispositionNotAttempted = "not_attempted_this_session"
	DispositionProven       = "proven_equivalent"
	DispositionUnproven     = "unproven_queued_for_human_review"
)

// RemediationOutcome is one real agent-remediation attempt's result, written
// by the "remediate" subcommand and read back by "serve".
type RemediationOutcome struct {
	ID              int    `json:"id"`
	ProposedAnsiSql string `json:"proposedAnsiSql"`
	Proven          bool   `json:"proven"`
	Detail          string `json:"detail"`
	Error           string `json:"error,omitempty"`
}

func loadStatementOutcomes(path string) ([]StatementOutcome, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var outcomes []StatementOutcome
	if err := json.Unmarshal(data, &outcomes); err != nil {
		return nil, err
	}
	return outcomes, nil
}

func refusedOnly(outcomes []StatementOutcome) []StatementOutcome {
	var refused []StatementOutcome
	for _, o := range outcomes {
		if o.Status == "refused" {
			refused = append(refused, o)
		}
	}
	sort.Slice(refused, func(i, j int) bool { return refused[i].ID < refused[j].ID })
	return refused
}

// buildQueue builds the full review queue from the transpiler's refusals,
// applying any real remediation outcomes on top. A refusal with no matching
// remediation outcome stays DispositionNotAttempted; this is never hidden
// as "proven" by default.
func buildQueue(refused []StatementOutcome, remediations map[int]RemediationOutcome) []QueueItem {
	items := make([]QueueItem, 0, len(refused))
	for _, r := range refused {
		item := QueueItem{
			ID:            r.ID,
			Kind:          r.Kind,
			LegacySql:     r.LegacySql,
			RefusalReason: r.RefusalReason,
			Disposition:   DispositionNotAttempted,
		}
		if rem, ok := remediations[r.ID]; ok {
			item.RemediationAttempted = true
			item.ProposedAnsiSql = rem.ProposedAnsiSql
			item.VerifierDetail = rem.Detail
			if rem.Proven {
				item.Disposition = DispositionProven
			} else {
				item.Disposition = DispositionUnproven
			}
		}
		items = append(items, item)
	}
	return items
}

func loadRemediations(path string) (map[int]RemediationOutcome, error) {
	result := map[int]RemediationOutcome{}
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return result, nil
	}
	if err != nil {
		return nil, err
	}
	var list []RemediationOutcome
	if err := json.Unmarshal(data, &list); err != nil {
		return nil, err
	}
	for _, r := range list {
		result[r.ID] = r
	}
	return result, nil
}

type Stats struct {
	TotalRefused     int `json:"totalRefused"`
	Attempted        int `json:"attempted"`
	ProvenEquivalent int `json:"provenEquivalent"`
	Unproven         int `json:"unproven"`
	NotAttempted     int `json:"notAttempted"`
}

func computeStats(items []QueueItem) Stats {
	s := Stats{TotalRefused: len(items)}
	for _, item := range items {
		switch item.Disposition {
		case DispositionProven:
			s.ProvenEquivalent++
			s.Attempted++
		case DispositionUnproven:
			s.Unproven++
			s.Attempted++
		default:
			s.NotAttempted++
		}
	}
	return s
}

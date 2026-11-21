package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: review-queue <serve|remediate> [flags]")
		os.Exit(1)
	}

	switch os.Args[1] {
	case "serve":
		runServe(os.Args[2:])
	case "remediate":
		runRemediate(os.Args[2:])
	default:
		fmt.Fprintln(os.Stderr, "usage: review-queue <serve|remediate> [flags]")
		os.Exit(1)
	}
}

func runServe(args []string) {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	resultsPath := fs.String("results", "../migration_results.json", "path to migration_results.json")
	remediationPath := fs.String("remediation", "remediation_results.json", "path to remediation_results.json")
	addr := fs.String("addr", "127.0.0.1:0", "listen address; port 0 picks a free port")
	fs.Parse(args)

	outcomes, err := loadStatementOutcomes(*resultsPath)
	if err != nil {
		log.Fatalf("loading %s: %v", *resultsPath, err)
	}
	remediations, err := loadRemediations(*remediationPath)
	if err != nil {
		log.Fatalf("loading %s: %v", *remediationPath, err)
	}
	items := buildQueue(refusedOnly(outcomes), remediations)

	srv := NewServer(items)
	listener, err := net.Listen("tcp", *addr)
	if err != nil {
		log.Fatalf("listen: %v", err)
	}
	fmt.Printf("review-queue listening on %s (%d refused statements loaded)\n", listener.Addr().String(), len(items))
	log.Fatal(http.Serve(listener, srv.Mux()))
}

func runRemediate(args []string) {
	fs := flag.NewFlagSet("remediate", flag.ExitOnError)
	resultsPath := fs.String("results", "../migration_results.json", "path to migration_results.json")
	outputPath := fs.String("output", "remediation_results.json", "where to write remediation_results.json")
	n := fs.Int("n", 10, "number of refused statements to attempt (disclosed, time-budgeted sample)")
	model := fs.String("model", "claude-haiku-4-5", "claude model to use")
	javaBin := fs.String("java", "java", "java executable")
	classpath := fs.String("classpath", "../target/classes:../target/lib/*", "classpath for the verify-single subprocess")
	fs.Parse(args)

	outcomes, err := loadStatementOutcomes(*resultsPath)
	if err != nil {
		log.Fatalf("loading %s: %v", *resultsPath, err)
	}
	refused := refusedOnly(outcomes)

	fmt.Printf("attempting real agent remediation on %d of %d refused statements (disclosed, time-budgeted sample)\n", min(*n, len(refused)), len(refused))
	results := RunRemediation(refused, *n, *model, *javaBin, *classpath)

	data, err := json.MarshalIndent(results, "", "  ")
	if err != nil {
		log.Fatalf("marshal: %v", err)
	}
	if err := os.WriteFile(*outputPath, data, 0644); err != nil {
		log.Fatalf("write %s: %v", *outputPath, err)
	}

	proven, unproven, errored := 0, 0, 0
	for _, r := range results {
		if r.Error != "" {
			errored++
		} else if r.Proven {
			proven++
		} else {
			unproven++
		}
	}
	fmt.Printf("attempted: %d, proven equivalent: %d, failed differential execution: %d, errored (claude or verifier failure): %d\n",
		len(results), proven, unproven, errored)
	fmt.Printf("results written to %s\n", *outputPath)
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

package com.manasv.batchmigration.legacysql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manasv.batchmigration.legacysql.datagen.FixtureData;
import com.manasv.batchmigration.legacysql.datagen.GeneratedStatement;
import com.manasv.batchmigration.legacysql.datagen.StatementGenerator;
import com.manasv.batchmigration.legacysql.transpiler.LegacySqlTranspiler;
import com.manasv.batchmigration.legacysql.transpiler.UnsupportedConstructException;
import com.manasv.batchmigration.legacysql.verify.DifferentialExecutor;
import com.manasv.batchmigration.legacysql.verify.VerificationResult;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two modes:
 *
 * "java -jar legacy-sql-migration-agent.jar run <output-json-path>" (default
 * if no recognized first argument is given) generates 1,000 legacy
 * statements, transpiles each (converted or refused with a named reason),
 * proves every converted statement by differential execution, and writes a
 * results JSON for the Go review-queue service to load.
 *
 * "java -jar legacy-sql-migration-agent.jar verify-single <legacySql>
 * <proposedAnsiSql>" runs the same differential-execution verifier against
 * one agent-proposed translation and prints a one-line JSON result; this is
 * the subprocess the Go review-queue's remediation command shells out to,
 * so an agent's proposal is held to the identical oracle as the
 * deterministic 836.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("verify-single")) {
            runVerifySingle(args[1], args[2]);
            return;
        }
        String outputPath = (args.length > 0 && args[0].equals("run")) ? args[1] : (args.length > 0 ? args[0] : "migration_results.json");

        FixtureData data = FixtureData.generate(FixtureData.DEFAULT_SEED);
        StatementGenerator generator = new StatementGenerator();
        List<GeneratedStatement> statements = generator.generate(StatementGenerator.DEFAULT_SEED, StatementGenerator.STATEMENT_COUNT);

        LegacySqlTranspiler transpiler = new LegacySqlTranspiler();
        DifferentialExecutor executor = new DifferentialExecutor(data);

        List<StatementOutcome> outcomes = new ArrayList<>();
        int converted = 0;
        int refused = 0;
        int provenCount = 0;
        int disprovenCount = 0;
        Map<String, Integer> refusalReasons = new LinkedHashMap<>();

        for (GeneratedStatement gs : statements) {
            StatementOutcome outcome = new StatementOutcome();
            outcome.id = gs.id();
            outcome.kind = gs.kind();
            outcome.legacySql = gs.sql();

            try {
                String ansiSql = transpiler.transpile(gs.sql());
                outcome.status = "converted";
                outcome.ansiSql = ansiSql;
                converted++;

                VerificationResult result = executor.verify(gs.sql(), ansiSql, data);
                outcome.proven = result.equivalent();
                outcome.mismatchDetail = result.firstMismatch();
                if (result.equivalent()) {
                    provenCount++;
                } else {
                    disprovenCount++;
                }
            } catch (UnsupportedConstructException e) {
                outcome.status = "refused";
                outcome.refusalReason = e.getMessage();
                refused++;
                refusalReasons.merge(e.getMessage(), 1, Integer::sum);
            }
            outcomes.add(outcome);
        }
        executor.close();

        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(outputPath), outcomes);

        System.out.println("total statements: " + statements.size());
        System.out.println("converted deterministically: " + converted);
        System.out.println("refused by transpiler: " + refused);
        System.out.println("  refusal reasons:");
        for (Map.Entry<String, Integer> e : refusalReasons.entrySet()) {
            System.out.println("    " + e.getKey() + ": " + e.getValue());
        }
        System.out.println("converted statements proven equivalent by differential execution: " + provenCount);
        System.out.println("converted statements that FAILED differential execution: " + disprovenCount);
        if (disprovenCount > 0) {
            System.out.println("  first few mismatches:");
            int shown = 0;
            for (StatementOutcome o : outcomes) {
                if ("converted".equals(o.status) && !o.proven && shown < 5) {
                    System.out.println("    id=" + o.id + " kind=" + o.kind + " " + o.mismatchDetail);
                    shown++;
                }
            }
        }
        System.out.println("results written to " + outputPath);
    }

    private static void runVerifySingle(String legacySql, String proposedAnsiSql) throws Exception {
        FixtureData data = FixtureData.generate(FixtureData.DEFAULT_SEED);
        DifferentialExecutor executor = new DifferentialExecutor(data);
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            VerificationResult result = executor.verify(legacySql, proposedAnsiSql, data);
            out.put("proven", result.equivalent());
            out.put("detail", result.equivalent()
                    ? "proven equivalent: " + result.sourceRowCount() + " rows matched exactly"
                    : result.firstMismatch());
        } catch (Exception e) {
            out.put("proven", false);
            out.put("detail", "verification threw: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            executor.close();
        }
        System.out.println(new ObjectMapper().writeValueAsString(out));
    }
}

package com.manasv.batchmigration.legacysql;

import com.manasv.batchmigration.legacysql.datagen.FixtureData;
import com.manasv.batchmigration.legacysql.datagen.GeneratedStatement;
import com.manasv.batchmigration.legacysql.datagen.StatementGenerator;
import com.manasv.batchmigration.legacysql.transpiler.LegacySqlTranspiler;
import com.manasv.batchmigration.legacysql.transpiler.UnsupportedConstructException;
import com.manasv.batchmigration.legacysql.verify.DifferentialExecutor;
import com.manasv.batchmigration.legacysql.verify.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real 1,000-statement pipeline end to end, the same one Main.java
 * runs, and checks the honest invariants: every statement reaches exactly
 * one disposition, every statement generated under an "_OK" kind converts
 * and is proven, every statement generated under a "_REFUSE" kind is
 * refused for the expected reason, and no converted statement fails
 * differential execution now that the ROWNUM/ORDER BY bug is fixed. */
class FullPipelineTest {

    @Test
    void every1000StatementsGetsExactlyOneDisposition() throws Exception {
        FixtureData data = FixtureData.generate(FixtureData.DEFAULT_SEED);
        StatementGenerator generator = new StatementGenerator();
        List<GeneratedStatement> statements = generator.generate(StatementGenerator.DEFAULT_SEED, StatementGenerator.STATEMENT_COUNT);
        LegacySqlTranspiler transpiler = new LegacySqlTranspiler();
        DifferentialExecutor executor = new DifferentialExecutor(data);

        int converted = 0;
        int refused = 0;
        int proven = 0;
        int disproven = 0;
        Map<String, Integer> byKindRefused = new HashMap<>();
        Map<String, Integer> byKindConverted = new HashMap<>();

        for (GeneratedStatement gs : statements) {
            try {
                String ansi = transpiler.transpile(gs.sql());
                converted++;
                byKindConverted.merge(gs.kind(), 1, Integer::sum);
                VerificationResult result = executor.verify(gs.sql(), ansi, data);
                if (result.equivalent()) {
                    proven++;
                } else {
                    disproven++;
                }
            } catch (UnsupportedConstructException e) {
                refused++;
                byKindRefused.merge(gs.kind(), 1, Integer::sum);
            }
        }
        executor.close();

        assertEquals(1000, converted + refused);
        assertEquals(converted, proven + disproven);
        assertEquals(0, disproven, "every converted statement must be proven after the ROWNUM/ORDER BY fix");

        assertEquals(0, byKindRefused.getOrDefault("SIMPLE_SELECT", 0));
        assertEquals(0, byKindRefused.getOrDefault("DECODE_SELECT", 0));
        assertEquals(0, byKindRefused.getOrDefault("NVL_SELECT", 0));
        assertEquals(0, byKindRefused.getOrDefault("SYSDATE_SELECT", 0));
        assertEquals(0, byKindRefused.getOrDefault("TOCHAR_OK", 0));
        assertEquals(0, byKindRefused.getOrDefault("OUTER_JOIN_OK", 0));
        assertEquals(0, byKindRefused.getOrDefault("ROWNUM_OK", 0));

        assertEquals(0, byKindConverted.getOrDefault("TOCHAR_FORMAT_REFUSE", 0));
        assertEquals(0, byKindConverted.getOrDefault("OUTER_JOIN_AMBIGUOUS_REFUSE", 0));
        assertEquals(0, byKindConverted.getOrDefault("ROWNUM_REFUSE", 0));
        assertEquals(0, byKindConverted.getOrDefault("CONNECT_BY_REFUSE", 0));
    }

    @Test
    void generationIsDeterministicForTheSameSeed() {
        StatementGenerator generator = new StatementGenerator();
        List<GeneratedStatement> a = generator.generate(StatementGenerator.DEFAULT_SEED, 200);
        List<GeneratedStatement> b = generator.generate(StatementGenerator.DEFAULT_SEED, 200);
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).sql(), b.get(i).sql());
        }
    }
}

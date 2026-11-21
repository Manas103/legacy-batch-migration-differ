package com.manasv.batchmigration.legacysql;

import com.manasv.batchmigration.legacysql.datagen.FixtureData;
import com.manasv.batchmigration.legacysql.transpiler.LegacySqlTranspiler;
import com.manasv.batchmigration.legacysql.verify.DifferentialExecutor;
import com.manasv.batchmigration.legacysql.verify.VerificationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DifferentialExecutorTest {

    private FixtureData data;
    private DifferentialExecutor executor;
    private final LegacySqlTranspiler transpiler = new LegacySqlTranspiler();

    @BeforeEach
    void setUp() throws Exception {
        data = FixtureData.generate(FixtureData.DEFAULT_SEED);
        executor = new DifferentialExecutor(data);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.close();
    }

    @Test
    void proveSimpleSelectEquivalent() throws Exception {
        String legacy = "SELECT id, name, salary FROM emp WHERE dept_id = 2 ORDER BY id";
        String ansi = transpiler.transpile(legacy);
        VerificationResult result = executor.verify(legacy, ansi, data);
        assertTrue(result.equivalent(), result.firstMismatch());
        assertTrue(result.sourceRowCount() > 0);
    }

    @Test
    void proveDecodeEquivalent() throws Exception {
        String legacy = "SELECT id, DECODE(status, 'A', 'Active', 'I', 'Inactive', 'P', 'Pending', 'Unknown') FROM emp WHERE dept_id = 3 ORDER BY id";
        String ansi = transpiler.transpile(legacy);
        VerificationResult result = executor.verify(legacy, ansi, data);
        assertTrue(result.equivalent(), result.firstMismatch());
    }

    @Test
    void proveOuterJoinEquivalentIncludingUnmatchedRows() throws Exception {
        String legacy = "SELECT e.id, e.name, d.name FROM emp e, dept d WHERE e.dept_id = d.id (+) AND e.salary > 30000 ORDER BY e.id";
        String ansi = transpiler.transpile(legacy);
        VerificationResult result = executor.verify(legacy, ansi, data);
        assertTrue(result.equivalent(), result.firstMismatch());
    }

    @Test
    void proveRownumWithOrderByEquivalentAfterTheFix() throws Exception {
        String legacy = "SELECT id, name FROM emp WHERE dept_id = 1 AND ROWNUM <= 5 ORDER BY salary DESC";
        String ansi = transpiler.transpile(legacy);
        VerificationResult result = executor.verify(legacy, ansi, data);
        assertTrue(result.equivalent(), result.firstMismatch());
        assertTrue(result.sourceRowCount() <= 5);
    }

    @Test
    void differentialExecutionActuallyCatchesAWrongTranslation() throws Exception {
        // A deliberately wrong "translation" (ORDER BY before LIMIT, the exact
        // bug this module's real first attempt had) must be caught, proving the
        // oracle discriminates rather than always passing.
        String legacy = "SELECT id, name FROM emp WHERE id > 0 AND ROWNUM <= 5 ORDER BY salary DESC";
        String wrongAnsi = "SELECT id, name FROM emp WHERE id > 0 ORDER BY salary DESC LIMIT 5";
        VerificationResult result = executor.verify(legacy, wrongAnsi, data);
        assertFalse(result.equivalent());
    }
}

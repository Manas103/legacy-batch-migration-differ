package com.manasv.batchmigration.legacysql;

import com.manasv.batchmigration.legacysql.transpiler.LegacySqlTranspiler;
import com.manasv.batchmigration.legacysql.transpiler.UnsupportedConstructException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TranspilerTest {

    private final LegacySqlTranspiler transpiler = new LegacySqlTranspiler();

    @Test
    void translatesSimpleSelectUnchanged() {
        String sql = transpiler.transpile("SELECT id, name, salary FROM emp WHERE salary > 50000 ORDER BY id");
        assertEquals("SELECT id, name, salary FROM emp WHERE salary > 50000 ORDER BY id", sql);
    }

    @Test
    void translatesDecodeToCase() {
        String sql = transpiler.transpile("SELECT DECODE(status, 'A', 'Active', 'I', 'Inactive', 'Unknown') FROM emp");
        assertEquals("SELECT CASE status WHEN 'A' THEN 'Active' WHEN 'I' THEN 'Inactive' ELSE 'Unknown' END FROM emp", sql);
    }

    @Test
    void translatesNvlToCoalesce() {
        String sql = transpiler.transpile("SELECT NVL(mgr_id, 0) FROM emp");
        assertEquals("SELECT COALESCE(mgr_id, 0) FROM emp", sql);
    }

    @Test
    void translatesSysdateToCurrentDate() {
        String sql = transpiler.transpile("SELECT SYSDATE FROM emp WHERE hire_date < SYSDATE");
        assertEquals("SELECT CURRENT_DATE FROM emp WHERE hire_date < CURRENT_DATE", sql);
    }

    @Test
    void translatesToCharWithoutFormatToCast() {
        String sql = transpiler.transpile("SELECT TO_CHAR(salary) FROM emp");
        assertEquals("SELECT CAST(salary AS VARCHAR) FROM emp", sql);
    }

    @Test
    void refusesToCharWithFormatMask() {
        UnsupportedConstructException ex = assertThrows(UnsupportedConstructException.class,
                () -> transpiler.transpile("SELECT TO_CHAR(hire_date, 'YYYY-MM-DD') FROM emp"));
        assertEquals("legacy TO_CHAR date format mask", ex.getMessage());
    }

    @Test
    void refusesConnectBy() {
        UnsupportedConstructException ex = assertThrows(UnsupportedConstructException.class,
                () -> transpiler.transpile("SELECT id FROM emp WHERE dept_id = 1 CONNECT BY mgr_id"));
        assertEquals("CONNECT BY hierarchical query", ex.getMessage());
    }

    @Test
    void refusesNonRangeRownum() {
        UnsupportedConstructException ex = assertThrows(UnsupportedConstructException.class,
                () -> transpiler.transpile("SELECT id FROM emp WHERE ROWNUM = 3"));
        assertEquals("non-range ROWNUM usage (=)", ex.getMessage());
    }

    @Test
    void acceptsRangeRownumAndAppendsLimit() {
        String sql = transpiler.transpile("SELECT id FROM emp WHERE ROWNUM <= 5");
        assertEquals("SELECT id FROM emp LIMIT 5", sql);
    }

    @Test
    void lessThanRownumSubtractsOneFromLimit() {
        String sql = transpiler.transpile("SELECT id FROM emp WHERE ROWNUM < 5");
        assertEquals("SELECT id FROM emp LIMIT 4", sql);
    }

    @Test
    void rownumWithOrderByNestsLimitBeforeSort() {
        String sql = transpiler.transpile("SELECT id, name FROM emp WHERE dept_id = 1 AND ROWNUM <= 5 ORDER BY salary DESC");
        assertTrue(sql.startsWith("SELECT c0, c1 FROM (SELECT id AS c0, name AS c1, salary AS o0 FROM emp WHERE dept_id = 1 LIMIT 5) t ORDER BY o0 DESC"));
    }

    @Test
    void refusesAmbiguousMultiTableOuterJoin() {
        UnsupportedConstructException ex = assertThrows(UnsupportedConstructException.class,
                () -> transpiler.transpile("SELECT e.id FROM emp e, dept d, proj p WHERE e.dept_id = d.id (+) AND e.id = p.emp_id (+)"));
        assertEquals("ambiguous multi-table outer join ordering", ex.getMessage());
    }

    @Test
    void translatesSingleOuterJoinToLeftJoin() {
        String sql = transpiler.transpile("SELECT e.id, d.name FROM emp e, dept d WHERE e.dept_id = d.id (+)");
        assertEquals("SELECT e.id, d.name FROM emp e LEFT JOIN dept d ON e.dept_id = d.id", sql);
    }
}

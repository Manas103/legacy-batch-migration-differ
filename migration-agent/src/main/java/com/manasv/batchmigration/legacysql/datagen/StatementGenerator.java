package com.manasv.batchmigration.legacysql.datagen;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seeded generator of 1,000 legacy SQL SELECT statements over the EMP/DEPT/PROJ
 * fixture tables in FixtureData. Each statement is tagged with the construct
 * "kind" it was generated to exercise, for bookkeeping only; the transpiler
 * decides its fate on the SQL text alone, not on this tag.
 */
public class StatementGenerator {

    public static final int DEFAULT_SEED = 20260701;
    public static final int STATEMENT_COUNT = 1000;

    private static final String[] STATUSES = {"A", "I", "P"};

    public List<GeneratedStatement> generate(long seed, int count) {
        Random rng = new Random(seed);
        List<GeneratedStatement> out = new ArrayList<>();
        for (int id = 1; id <= count; id++) {
            out.add(buildOne(id, rng));
        }
        return out;
    }

    private GeneratedStatement buildOne(int id, Random rng) {
        double r = rng.nextDouble() * 100.0;
        if (r < 30) return simpleSelect(id, rng);
        if (r < 40) return decodeSelect(id, rng);
        if (r < 48) return nvlSelect(id, rng);
        if (r < 55) return sysdateSelect(id, rng);
        if (r < 63) return toCharOk(id, rng);
        if (r < 68) return toCharFormatRefuse(id, rng);
        if (r < 83) return outerJoinOk(id, rng);
        if (r < 88) return outerJoinAmbiguousRefuse(id, rng);
        if (r < 95) return rownumOk(id, rng);
        if (r < 98) return rownumRefuse(id, rng);
        return connectByRefuse(id, rng);
    }

    private GeneratedStatement simpleSelect(int id, Random rng) {
        int kind = rng.nextInt(3);
        String where;
        if (kind == 0) {
            where = "WHERE salary > " + (30000 + rng.nextInt(70000));
        } else if (kind == 1) {
            where = "WHERE status = '" + STATUSES[rng.nextInt(STATUSES.length)] + "'";
        } else {
            where = "WHERE dept_id = " + (1 + rng.nextInt(8));
        }
        String sql = "SELECT id, name, salary FROM emp " + where + " ORDER BY id";
        return new GeneratedStatement(id, "SIMPLE_SELECT", sql);
    }

    private GeneratedStatement decodeSelect(int id, Random rng) {
        String sql = "SELECT id, name, DECODE(status, 'A', 'Active', 'I', 'Inactive', 'P', 'Pending', 'Unknown') FROM emp WHERE dept_id = " + (1 + rng.nextInt(8)) + " ORDER BY id";
        return new GeneratedStatement(id, "DECODE_SELECT", sql);
    }

    private GeneratedStatement nvlSelect(int id, Random rng) {
        String sql = "SELECT id, name, NVL(mgr_id, 0) FROM emp WHERE salary > " + (30000 + rng.nextInt(70000)) + " ORDER BY id";
        return new GeneratedStatement(id, "NVL_SELECT", sql);
    }

    private GeneratedStatement sysdateSelect(int id, Random rng) {
        String sql = "SELECT id, name FROM emp WHERE hire_date < SYSDATE AND dept_id = " + (1 + rng.nextInt(8)) + " ORDER BY id";
        return new GeneratedStatement(id, "SYSDATE_SELECT", sql);
    }

    private GeneratedStatement toCharOk(int id, Random rng) {
        String sql = "SELECT id, TO_CHAR(salary) FROM emp WHERE dept_id = " + (1 + rng.nextInt(8)) + " ORDER BY id";
        return new GeneratedStatement(id, "TOCHAR_OK", sql);
    }

    private GeneratedStatement toCharFormatRefuse(int id, Random rng) {
        String sql = "SELECT id, TO_CHAR(hire_date, 'YYYY-MM-DD') FROM emp WHERE dept_id = " + (1 + rng.nextInt(8)) + " ORDER BY id";
        return new GeneratedStatement(id, "TOCHAR_FORMAT_REFUSE", sql);
    }

    private GeneratedStatement outerJoinOk(int id, Random rng) {
        String sql = "SELECT e.id, e.name, d.name FROM emp e, dept d WHERE e.dept_id = d.id (+) AND e.salary > " + (30000 + rng.nextInt(70000)) + " ORDER BY e.id";
        return new GeneratedStatement(id, "OUTER_JOIN_OK", sql);
    }

    private GeneratedStatement outerJoinAmbiguousRefuse(int id, Random rng) {
        String sql = "SELECT e.id, e.name, d.name FROM emp e, dept d, proj p WHERE e.dept_id = d.id (+) AND e.id = p.emp_id (+) ORDER BY e.id";
        return new GeneratedStatement(id, "OUTER_JOIN_AMBIGUOUS_REFUSE", sql);
    }

    private GeneratedStatement rownumOk(int id, Random rng) {
        int n = 3 + rng.nextInt(10);
        String op = rng.nextBoolean() ? "<=" : "<";
        // ORDER BY a column other than the scan order (id) deliberately: Oracle
        // assigns ROWNUM before ORDER BY runs, so "first N in scan order, then
        // sorted" is not the same set as "sorted, then first N". See README
        // Findings for the real mismatch this exposed on the first transpiler attempt.
        String sql = "SELECT id, name FROM emp WHERE dept_id = " + (1 + rng.nextInt(8)) + " AND ROWNUM " + op + " " + n + " ORDER BY salary DESC";
        return new GeneratedStatement(id, "ROWNUM_OK", sql);
    }

    private GeneratedStatement rownumRefuse(int id, Random rng) {
        String op = List.of("=", ">", ">=", "<>").get(rng.nextInt(4));
        int n = 1 + rng.nextInt(10);
        String sql = "SELECT id, name FROM emp WHERE ROWNUM " + op + " " + n + " ORDER BY id";
        return new GeneratedStatement(id, "ROWNUM_REFUSE", sql);
    }

    private GeneratedStatement connectByRefuse(int id, Random rng) {
        String sql = "SELECT id, name FROM emp WHERE dept_id = " + (1 + rng.nextInt(8)) + " CONNECT BY mgr_id";
        return new GeneratedStatement(id, "CONNECT_BY_REFUSE", sql);
    }
}

package com.manasv.batchmigration.legacysql.datagen;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Seeded, in-memory EMP/DEPT/PROJ fixture rows, shared verbatim between the
 * legacy reference interpreter (reads this list directly) and the real H2
 * database the transpiled SQL runs against (loaded via the INSERT
 * statements this class also generates). No real business data. */
public class FixtureData {

    public static final int EMP_COUNT = 60;
    public static final int DEPT_COUNT = 8;
    public static final int PROJ_COUNT = 30;
    public static final long DEFAULT_SEED = 20260701;

    private static final String[] STATUSES = {"A", "I", "P"};
    private static final String[] FIRST_NAMES = {"Alex", "Jordan", "Sam", "Taylor", "Morgan", "Casey", "Riley", "Jamie"};
    private static final String[] LAST_NAMES = {"Lee", "Patel", "Garcia", "Kim", "Nguyen", "Brown", "Chen", "Davis"};
    private static final String[] REGIONS = {"northeast", "southeast", "midwest", "west"};

    public final List<Map<String, Object>> emp = new ArrayList<>();
    public final List<Map<String, Object>> dept = new ArrayList<>();
    public final List<Map<String, Object>> proj = new ArrayList<>();

    public static FixtureData generate(long seed) {
        Random rng = new Random(seed);
        FixtureData f = new FixtureData();

        for (int id = 1; id <= DEPT_COUNT; id++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", "dept_" + id);
            row.put("region", REGIONS[rng.nextInt(REGIONS.length)]);
            f.dept.add(row);
        }

        for (int id = 1; id <= EMP_COUNT; id++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", FIRST_NAMES[rng.nextInt(FIRST_NAMES.length)] + " " + LAST_NAMES[rng.nextInt(LAST_NAMES.length)]);
            // 1-in-12 employees reports to a department that does not exist, to give
            // the outer-join predicate real NULL-producing rows to prove, not just
            // a matching inner join in disguise.
            row.put("dept_id", rng.nextInt(12) == 0 ? DEPT_COUNT + 1 : 1 + rng.nextInt(DEPT_COUNT));
            row.put("salary", 30000 + rng.nextInt(90000));
            row.put("hire_date", LocalDate.of(2015 + rng.nextInt(10), 1 + rng.nextInt(12), 1 + rng.nextInt(28)));
            row.put("status", STATUSES[rng.nextInt(STATUSES.length)]);
            row.put("mgr_id", id == 1 ? null : 1 + rng.nextInt(id - 1));
            f.emp.add(row);
        }

        for (int id = 1; id <= PROJ_COUNT; id++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("emp_id", 1 + rng.nextInt(EMP_COUNT));
            row.put("title", "project_" + id);
            f.proj.add(row);
        }
        return f;
    }

    public List<String> buildSchemaAndInsertStatements() {
        List<String> stmts = new ArrayList<>();
        stmts.add("CREATE TABLE emp (id INT PRIMARY KEY, name VARCHAR(60), dept_id INT, salary INT, hire_date DATE, status VARCHAR(10), mgr_id INT)");
        stmts.add("CREATE TABLE dept (id INT PRIMARY KEY, name VARCHAR(60), region VARCHAR(20))");
        stmts.add("CREATE TABLE proj (id INT PRIMARY KEY, emp_id INT, title VARCHAR(60))");
        for (Map<String, Object> row : dept) {
            stmts.add("INSERT INTO dept VALUES (" + row.get("id") + ", '" + row.get("name") + "', '" + row.get("region") + "')");
        }
        for (Map<String, Object> row : emp) {
            Object mgr = row.get("mgr_id");
            stmts.add("INSERT INTO emp VALUES (" + row.get("id") + ", '" + row.get("name") + "', " + row.get("dept_id") + ", " + row.get("salary") + ", DATE '" + row.get("hire_date") + "', '" + row.get("status") + "', " + (mgr == null ? "NULL" : mgr) + ")");
        }
        for (Map<String, Object> row : proj) {
            stmts.add("INSERT INTO proj VALUES (" + row.get("id") + ", " + row.get("emp_id") + ", '" + row.get("title") + "')");
        }
        return stmts;
    }
}

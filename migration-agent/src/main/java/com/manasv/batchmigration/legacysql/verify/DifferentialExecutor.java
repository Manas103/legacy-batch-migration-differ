package com.manasv.batchmigration.legacysql.verify;

import com.manasv.batchmigration.legacysql.LegacySqlLexer;
import com.manasv.batchmigration.legacysql.LegacySqlParser;
import com.manasv.batchmigration.legacysql.datagen.FixtureData;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Proves a transpiled statement equivalent to its legacy source by running
 * the source through LegacyInterpreter (in-memory, no database) and the
 * transpiled ANSI SQL through a real H2 database loaded with the identical
 * fixture rows, then diffing the two result sets row by row, value by
 * value, after normalizing each side's Java types to a common form.
 */
public class DifferentialExecutor {

    private final Connection h2;
    private final LegacyInterpreter interpreter = new LegacyInterpreter();

    public DifferentialExecutor(FixtureData data) throws SQLException {
        this.h2 = DriverManager.getConnection("jdbc:h2:mem:migration_target_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Statement st = h2.createStatement()) {
            for (String sql : data.buildSchemaAndInsertStatements()) {
                st.execute(sql);
            }
        }
    }

    public void close() throws SQLException {
        h2.close();
    }

    public VerificationResult verify(String legacySql, String ansiSql, FixtureData data) throws SQLException {
        LegacySqlLexer lexer = new LegacySqlLexer(CharStreams.fromString(legacySql));
        LegacySqlParser parser = new LegacySqlParser(new CommonTokenStream(lexer));
        LegacySqlParser.SelectStatementContext stmt = parser.selectStatement();

        List<List<Object>> sourceRows = interpreter.execute(stmt, data);
        List<List<Object>> normalizedSource = new ArrayList<>();
        for (List<Object> row : sourceRows) {
            List<Object> norm = new ArrayList<>();
            for (Object v : row) {
                norm.add(normalize(v));
            }
            normalizedSource.add(norm);
        }

        List<List<Object>> targetRows = runAnsi(ansiSql);

        if (normalizedSource.size() != targetRows.size()) {
            return VerificationResult.mismatch(normalizedSource.size(), targetRows.size(),
                    "row count differs: source=" + normalizedSource.size() + " target=" + targetRows.size());
        }
        for (int i = 0; i < normalizedSource.size(); i++) {
            List<Object> s = normalizedSource.get(i);
            List<Object> t = targetRows.get(i);
            if (!s.equals(t)) {
                return VerificationResult.mismatch(normalizedSource.size(), targetRows.size(),
                        "row " + i + " differs: source=" + s + " target=" + t);
            }
        }
        return VerificationResult.ok(normalizedSource.size());
    }

    private List<List<Object>> runAnsi(String ansiSql) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (Statement st = h2.createStatement(); ResultSet rs = st.executeQuery(ansiSql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int c = 1; c <= cols; c++) {
                    row.add(normalize(rs.getObject(c)));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private Object normalize(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof LocalDate) {
            return v;
        }
        if (v instanceof Number n) {
            if (n.doubleValue() == Math.floor(n.doubleValue()) && !(n instanceof Double && n.toString().contains("E"))) {
                return n.longValue();
            }
            return n.doubleValue();
        }
        if (v instanceof String s) {
            return s.trim();
        }
        return v;
    }
}

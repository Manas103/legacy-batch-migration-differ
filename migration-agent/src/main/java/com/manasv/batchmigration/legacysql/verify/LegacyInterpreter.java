package com.manasv.batchmigration.legacysql.verify;

import com.manasv.batchmigration.legacysql.LegacySqlParser;
import com.manasv.batchmigration.legacysql.datagen.FixtureData;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reference oracle: a deliberately literal, obviously-correct
 * tree-walking evaluator of the legacy dialect's own semantics, run
 * directly against the same in-memory fixture rows the real database is
 * loaded from. It shares no code with LegacySqlTranspiler; the only thing
 * the two have in common is the ANTLR parse tree they both start from.
 *
 * Deliberately faithful to one easy-to-miss Oracle rule: ROWNUM is assigned
 * to rows as they pass every other WHERE condition, in scan order, strictly
 * before ORDER BY runs. A statement like
 * "WHERE dept_id = 1 AND ROWNUM &lt;= 5 ORDER BY salary DESC" takes the
 * first 5 qualifying rows in table-scan order, then sorts just those 5; it
 * does not take the top 5 by salary. See README Findings for the real
 * transpiler bug this exposed.
 */
public class LegacyInterpreter {

    @SuppressWarnings("unchecked")
    public List<List<Object>> execute(LegacySqlParser.SelectStatementContext stmt, FixtureData data) {
        List<LegacySqlParser.TableRefContext> tableRefs = stmt.tableRefList().tableRef();
        List<LegacySqlParser.PredicateContext> outerJoinPredicates = findOuterJoinPredicates(stmt);
        List<Map<String, Object>> joined = joinAllTables(tableRefs, outerJoinPredicates, data);

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : joined) {
            if (passesOtherPredicates(stmt, row, outerJoinPredicates)) {
                filtered.add(row);
            }
        }

        List<Map<String, Object>> rownumLimited = applyRownum(stmt, filtered);

        List<Map<String, Object>> ordered = applyOrderBy(stmt, rownumLimited);

        List<List<Object>> out = new ArrayList<>();
        for (Map<String, Object> row : ordered) {
            List<Object> projected = new ArrayList<>();
            for (LegacySqlParser.SelectItemContext item : stmt.selectList().selectItem()) {
                projected.add(evalExpr(item.expr(), row));
            }
            out.add(projected);
        }
        return out;
    }

    private List<Map<String, Object>> rowsFor(LegacySqlParser.TableRefContext ref, FixtureData data) {
        String name = ref.IDENT(0).getText().toLowerCase();
        return switch (name) {
            case "emp" -> data.emp;
            case "dept" -> data.dept;
            case "proj" -> data.proj;
            default -> throw new IllegalArgumentException("unknown table: " + name);
        };
    }

    private String aliasOf(LegacySqlParser.TableRefContext ref) {
        List<org.antlr.v4.runtime.tree.TerminalNode> idents = ref.IDENT();
        return idents.size() > 1 ? idents.get(1).getText() : idents.get(0).getText();
    }

    private Map<String, Object> flatten(LegacySqlParser.TableRefContext ref, Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        String alias = aliasOf(ref);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            out.put(alias + "." + e.getKey(), e.getValue());
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private List<LegacySqlParser.PredicateContext> findOuterJoinPredicates(LegacySqlParser.SelectStatementContext stmt) {
        List<LegacySqlParser.PredicateContext> result = new ArrayList<>();
        if (stmt.whereClause() == null) {
            return result;
        }
        for (LegacySqlParser.PredicateContext p : stmt.whereClause().predicate()) {
            if (p.outerJoinMarker() != null) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Joins any number of tables: starts with the first table's rows, then
     * for each subsequent table either LEFT-joins it (if an outer-join
     * predicate references it, on its right-hand expr, matching the same
     * convention LegacySqlTranspiler assumes) or cross-joins it (every
     * combination, left for the later WHERE filter to narrow), accumulating
     * a merged row context as it goes so a predicate added for table N can
     * reference columns from any table 1..N-1 already incorporated.
     */
    private List<Map<String, Object>> joinAllTables(List<LegacySqlParser.TableRefContext> tableRefs,
                                                      List<LegacySqlParser.PredicateContext> outerJoinPredicates,
                                                      FixtureData data) {
        List<Map<String, Object>> working = new ArrayList<>();
        for (Map<String, Object> row : rowsFor(tableRefs.get(0), data)) {
            working.add(flatten(tableRefs.get(0), row));
        }

        for (int i = 1; i < tableRefs.size(); i++) {
            LegacySqlParser.TableRefContext ref = tableRefs.get(i);
            String alias = aliasOf(ref);
            LegacySqlParser.PredicateContext predicateForThisTable = null;
            for (LegacySqlParser.PredicateContext p : outerJoinPredicates) {
                if (tableNameOf(p.expr(1)).equals(alias)) {
                    predicateForThisTable = p;
                    break;
                }
            }
            List<Map<String, Object>> tableRows = rowsFor(ref, data);
            List<Map<String, Object>> next = new ArrayList<>();
            for (Map<String, Object> accRow : working) {
                boolean matched = false;
                for (Map<String, Object> tableRow : tableRows) {
                    Map<String, Object> merged = new LinkedHashMap<>(accRow);
                    merged.putAll(flattenSingle(ref, tableRow));
                    if (predicateForThisTable == null) {
                        next.add(merged);
                        matched = true;
                    } else {
                        Object left = evalExpr(predicateForThisTable.expr(0), merged);
                        Object right = evalExpr(predicateForThisTable.expr(1), merged);
                        if (left != null && left.equals(right)) {
                            next.add(merged);
                            matched = true;
                        }
                    }
                }
                if (!matched && predicateForThisTable != null) {
                    Map<String, Object> merged = new LinkedHashMap<>(accRow);
                    for (String col : tableRows.isEmpty() ? List.<String>of() : tableRows.get(0).keySet()) {
                        merged.put(alias + "." + col, null);
                    }
                    next.add(merged);
                }
            }
            working = next;
        }
        return working;
    }

    private String tableNameOf(LegacySqlParser.ExprContext expr) {
        String text = expr.getText();
        int dot = text.indexOf('.');
        return dot >= 0 ? text.substring(0, dot) : text;
    }

    private Map<String, Object> flattenSingle(LegacySqlParser.TableRefContext ref, Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        String alias = aliasOf(ref);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            out.put(alias + "." + e.getKey(), e.getValue());
        }
        return out;
    }

    private boolean passesOtherPredicates(LegacySqlParser.SelectStatementContext stmt, Map<String, Object> row, List<LegacySqlParser.PredicateContext> outerJoinPredicates) {
        if (stmt.whereClause() == null) {
            return true;
        }
        for (LegacySqlParser.PredicateContext p : stmt.whereClause().predicate()) {
            if (outerJoinPredicates.contains(p) || p.rownumPredicate() != null) {
                continue;
            }
            if (!evalComparison(evalExpr(p.expr(0), row), p.compOp().getText(), evalExpr(p.expr(1), row))) {
                return false;
            }
        }
        return true;
    }

    private List<Map<String, Object>> applyRownum(LegacySqlParser.SelectStatementContext stmt, List<Map<String, Object>> rows) {
        if (stmt.whereClause() == null) {
            return rows;
        }
        LegacySqlParser.PredicateContext rownumPred = null;
        for (LegacySqlParser.PredicateContext p : stmt.whereClause().predicate()) {
            if (p.rownumPredicate() != null) {
                rownumPred = p;
            }
        }
        if (rownumPred == null) {
            return rows;
        }
        String op = rownumPred.rownumPredicate().compOp().getText();
        int n = Integer.parseInt(rownumPred.rownumPredicate().NUMBER().getText());
        List<Map<String, Object>> out = new ArrayList<>();
        int counter = 0;
        for (Map<String, Object> row : rows) {
            counter++;
            boolean keep = switch (op) {
                case "<=" -> counter <= n;
                case "<" -> counter < n;
                default -> throw new IllegalStateException("unsupported rownum op reached interpreter: " + op);
            };
            if (keep) {
                out.add(row);
            }
        }
        return out;
    }

    private List<Map<String, Object>> applyOrderBy(LegacySqlParser.SelectStatementContext stmt, List<Map<String, Object>> rows) {
        if (stmt.orderList() == null) {
            return rows;
        }
        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        List<LegacySqlParser.OrderItemContext> items = stmt.orderList().orderItem();
        Comparator<Map<String, Object>> comparator = null;
        for (LegacySqlParser.OrderItemContext item : items) {
            boolean desc = item.DESC() != null;
            Comparator<Map<String, Object>> thisComparator = Comparator.comparing(
                    (Map<String, Object> row) -> (Comparable) evalExpr(item.expr(), row),
                    Comparator.nullsFirst(Comparator.naturalOrder())
            );
            if (desc) {
                thisComparator = thisComparator.reversed();
            }
            comparator = (comparator == null) ? thisComparator : comparator.thenComparing(thisComparator);
        }
        sorted.sort(comparator);
        return sorted;
    }

    private boolean evalComparison(Object left, String op, Object right) {
        if (left == null || right == null) {
            return false;
        }
        int cmp;
        if (left instanceof Comparable && right instanceof Comparable && left.getClass().equals(right.getClass())) {
            cmp = ((Comparable) left).compareTo(right);
        } else {
            cmp = left.toString().compareTo(right.toString());
        }
        return switch (op) {
            case "=" -> cmp == 0;
            case "<>" -> cmp != 0;
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            default -> throw new IllegalStateException("unknown op: " + op);
        };
    }

    @SuppressWarnings("unchecked")
    private Object evalExpr(LegacySqlParser.ExprContext ctx, Map<String, Object> row) {
        if (ctx instanceof LegacySqlParser.ArithExprContext arith) {
            double l = toDouble(evalExpr(arith.expr(0), row));
            double r = toDouble(evalExpr(arith.expr(1), row));
            String op = arith.arithOp().getText();
            return switch (op) {
                case "+" -> l + r;
                case "-" -> l - r;
                case "*" -> l * r;
                case "/" -> l / r;
                case "||" -> String.valueOf(evalExpr(arith.expr(0), row)) + evalExpr(arith.expr(1), row);
                default -> throw new IllegalStateException("unknown arith op: " + op);
            };
        }
        if (ctx instanceof LegacySqlParser.ParenExprContext paren) {
            return evalExpr(paren.expr(), row);
        }
        if (ctx instanceof LegacySqlParser.FuncExprContext func) {
            return evalFunctionCall(func.functionCall(), row);
        }
        if (ctx instanceof LegacySqlParser.ColumnExprContext col) {
            String key = col.getText().toLowerCase();
            if (!row.containsKey(key)) {
                throw new IllegalArgumentException("unknown column reference: " + key);
            }
            return row.get(key);
        }
        if (ctx instanceof LegacySqlParser.LiteralExprContext lit) {
            if (lit.literal().NUMBER() != null) {
                return Integer.parseInt(lit.literal().NUMBER().getText());
            }
            if (lit.literal().STRING() != null) {
                String s = lit.literal().STRING().getText();
                return s.substring(1, s.length() - 1);
            }
            throw new IllegalStateException("unsupported literal reached interpreter: " + lit.getText());
        }
        throw new IllegalStateException("unhandled expr: " + ctx.getClass());
    }

    private Object evalFunctionCall(LegacySqlParser.FunctionCallContext ctx, Map<String, Object> row) {
        if (ctx.DECODE() != null) {
            List<LegacySqlParser.ExprContext> args = ctx.expr();
            Object switchVal = evalExpr(args.get(0), row);
            int i = 1;
            for (; i + 1 < args.size(); i += 2) {
                Object candidate = evalExpr(args.get(i), row);
                if (switchVal != null && switchVal.equals(candidate)) {
                    return evalExpr(args.get(i + 1), row);
                }
            }
            return i < args.size() ? evalExpr(args.get(i), row) : null;
        }
        if (ctx.NVL() != null) {
            Object a = evalExpr(ctx.expr(0), row);
            return a != null ? a : evalExpr(ctx.expr(1), row);
        }
        if (ctx.SYSDATE() != null) {
            return LocalDate.now();
        }
        if (ctx.SUBSTR() != null) {
            String s = String.valueOf(evalExpr(ctx.expr(0), row));
            int pos = ((Number) evalExpr(ctx.expr(1), row)).intValue();
            int start = pos > 0 ? pos - 1 : 0;
            if (ctx.expr().size() > 2) {
                int len = ((Number) evalExpr(ctx.expr(2), row)).intValue();
                return s.substring(start, Math.min(s.length(), start + len));
            }
            return s.substring(Math.min(start, s.length()));
        }
        if (ctx.UPPER() != null) {
            return String.valueOf(evalExpr(ctx.expr(0), row)).toUpperCase();
        }
        if (ctx.LOWER() != null) {
            return String.valueOf(evalExpr(ctx.expr(0), row)).toLowerCase();
        }
        if (ctx.TO_CHAR() != null) {
            return String.valueOf(evalExpr(ctx.expr(0), row));
        }
        throw new IllegalStateException("unhandled function in interpreter: " + ctx.getText());
    }

    private double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException("not numeric: " + o);
    }
}

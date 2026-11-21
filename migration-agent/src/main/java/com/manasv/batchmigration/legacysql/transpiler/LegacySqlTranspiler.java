package com.manasv.batchmigration.legacysql.transpiler;

import com.manasv.batchmigration.legacysql.LegacySqlBaseVisitor;
import com.manasv.batchmigration.legacysql.LegacySqlLexer;
import com.manasv.batchmigration.legacysql.LegacySqlParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic transpiler: legacy Oracle-flavored SELECT statements to ANSI
 * SQL that H2's regular (non-Oracle-compatibility) mode runs unchanged.
 * Refuses (throws UnsupportedConstructException) rather than guessing on
 * four constructs it does not implement a translation rule for: CONNECT BY,
 * a ROWNUM comparison other than &lt;= or &lt;, more than one outer-join
 * (+) marker in the same statement, and TO_CHAR with a legacy format mask.
 * Everything else is translated by walking the ANTLR parse tree and
 * re-rendering it as ANSI text; no regex substitution on the raw SQL text
 * is used anywhere in this class.
 */
public class LegacySqlTranspiler {

    public String transpile(String legacySql) {
        LegacySqlLexer lexer = new LegacySqlLexer(CharStreams.fromString(legacySql));
        LegacySqlParser parser = new LegacySqlParser(new CommonTokenStream(lexer));
        LegacySqlParser.SelectStatementContext stmt = parser.selectStatement();

        if (stmt.CONNECT() != null) {
            throw new UnsupportedConstructException("CONNECT BY hierarchical query");
        }

        checkForUnsupportedToChar(stmt);

        List<LegacySqlParser.PredicateContext> predicates = new ArrayList<>();
        if (stmt.whereClause() != null) {
            predicates.addAll(stmt.whereClause().predicate());
        }

        LegacySqlParser.PredicateContext rownumPredicate = null;
        LegacySqlParser.PredicateContext outerJoinPredicate = null;
        int outerJoinCount = 0;
        for (LegacySqlParser.PredicateContext p : predicates) {
            if (p.rownumPredicate() != null) {
                String op = p.rownumPredicate().compOp().getText();
                if (!op.equals("<=") && !op.equals("<")) {
                    throw new UnsupportedConstructException("non-range ROWNUM usage (" + op + ")");
                }
                rownumPredicate = p;
            } else if (p.outerJoinMarker() != null) {
                outerJoinCount++;
                outerJoinPredicate = p;
            }
        }
        if (outerJoinCount >= 2) {
            throw new UnsupportedConstructException("ambiguous multi-table outer join ordering");
        }

        StringBuilder inner = new StringBuilder("SELECT ");
        inner.append(renderSelectList(stmt.selectList()));
        inner.append(" FROM ");
        inner.append(renderTableRefList(stmt.tableRefList(), outerJoinPredicate));

        List<String> normalPredicateTexts = new ArrayList<>();
        for (LegacySqlParser.PredicateContext p : predicates) {
            if (p == rownumPredicate || p == outerJoinPredicate) {
                continue;
            }
            normalPredicateTexts.add(renderPredicate(p));
        }
        if (!normalPredicateTexts.isEmpty()) {
            inner.append(" WHERE ").append(String.join(" AND ", normalPredicateTexts));
        }

        if (rownumPredicate == null) {
            if (stmt.orderList() != null) {
                inner.append(" ORDER BY ").append(renderOrderList(stmt.orderList()));
            }
            return inner.toString();
        }

        // Oracle assigns ROWNUM to rows in scan order, strictly before ORDER BY
        // runs: "WHERE x AND ROWNUM <= N ORDER BY y" takes the first N qualifying
        // rows in scan order, then sorts just those N. "... ORDER BY y LIMIT N"
        // would sort first and then take the top N by y instead, a different
        // result whenever the scan order and the ORDER BY column disagree. The
        // LIMIT must therefore apply to the unordered inner query, with ORDER BY
        // only in an outer wrapper around that already-limited row set. See
        // README Findings: the first version of this method did not nest the
        // subquery, and a real differential-execution run caught the mismatch.
        int n = Integer.parseInt(rownumPredicate.rownumPredicate().NUMBER().getText());
        int limit = rownumPredicate.rownumPredicate().compOp().getText().equals("<=") ? n : n - 1;

        if (stmt.orderList() == null) {
            inner.append(" LIMIT ").append(limit);
            return inner.toString();
        }

        // The ORDER BY column (e.g. salary) may not be one of the originally
        // selected columns, so it cannot be referenced from an outer query
        // wrapped around a subquery that only projects the original SELECT
        // list. The inner subquery below projects the original columns AND
        // every ORDER BY expression, under synthetic names, so the outer
        // query can sort by the hidden ones and still return only the
        // originally requested columns.
        List<LegacySqlParser.SelectItemContext> items = stmt.selectList().selectItem();
        List<LegacySqlParser.OrderItemContext> orderItems = stmt.orderList().orderItem();

        StringBuilder innerWithAliases = new StringBuilder("SELECT ");
        List<String> innerCols = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            innerCols.add(renderExpr(items.get(i).expr()) + " AS c" + i);
        }
        for (int j = 0; j < orderItems.size(); j++) {
            innerCols.add(renderExpr(orderItems.get(j).expr()) + " AS o" + j);
        }
        innerWithAliases.append(String.join(", ", innerCols));
        innerWithAliases.append(" FROM ").append(renderTableRefList(stmt.tableRefList(), outerJoinPredicate));
        if (!normalPredicateTexts.isEmpty()) {
            innerWithAliases.append(" WHERE ").append(String.join(" AND ", normalPredicateTexts));
        }
        innerWithAliases.append(" LIMIT ").append(limit);

        List<String> outerCols = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            outerCols.add("c" + i);
        }
        List<String> outerOrderBy = new ArrayList<>();
        for (int j = 0; j < orderItems.size(); j++) {
            String dir = orderItems.get(j).DESC() != null ? " DESC" : orderItems.get(j).ASC() != null ? " ASC" : "";
            outerOrderBy.add("o" + j + dir);
        }

        return "SELECT " + String.join(", ", outerCols) + " FROM (" + innerWithAliases + ") t ORDER BY " + String.join(", ", outerOrderBy);
    }

    private void checkForUnsupportedToChar(ParseTree tree) {
        new LegacySqlBaseVisitor<Void>() {
            @Override
            public Void visitFunctionCall(LegacySqlParser.FunctionCallContext ctx) {
                if (ctx.TO_CHAR() != null && ctx.STRING() != null) {
                    throw new UnsupportedConstructException("legacy TO_CHAR date format mask");
                }
                return visitChildren(ctx);
            }
        }.visit(tree);
    }

    private String renderSelectList(LegacySqlParser.SelectListContext ctx) {
        List<String> items = new ArrayList<>();
        for (LegacySqlParser.SelectItemContext item : ctx.selectItem()) {
            StringBuilder s = new StringBuilder(renderExpr(item.expr()));
            if (item.IDENT() != null) {
                s.append(" AS ").append(item.IDENT().getText());
            }
            items.add(s.toString());
        }
        return String.join(", ", items);
    }

    private String renderTableRefList(LegacySqlParser.TableRefListContext ctx, LegacySqlParser.PredicateContext outerJoinPredicate) {
        List<LegacySqlParser.TableRefContext> refs = ctx.tableRef();
        String outerTableName = null;
        String onCondition = null;
        if (outerJoinPredicate != null) {
            onCondition = renderExpr(outerJoinPredicate.expr(0)) + " " + outerJoinPredicate.compOp().getText() + " " + renderExpr(outerJoinPredicate.expr(1));
            outerTableName = tableNameOf(outerJoinPredicate.expr(1));
        }

        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < refs.size(); i++) {
            LegacySqlParser.TableRefContext ref = refs.get(i);
            List<String> identTexts = new ArrayList<>();
            for (org.antlr.v4.runtime.tree.TerminalNode t : ref.IDENT()) {
                identTexts.add(t.getText());
            }
            String refText = String.join(" ", identTexts);
            boolean isOuter = outerTableName != null && tableRefMatches(ref, outerTableName);
            if (i == 0) {
                sql.append(refText);
            } else if (isOuter) {
                sql.append(" LEFT JOIN ").append(refText).append(" ON ").append(onCondition);
            } else {
                sql.append(", ").append(refText);
            }
        }
        return sql.toString();
    }

    private boolean tableRefMatches(LegacySqlParser.TableRefContext ref, String name) {
        List<org.antlr.v4.runtime.tree.TerminalNode> idents = ref.IDENT();
        String effectiveName = idents.size() > 1 ? idents.get(1).getText() : idents.get(0).getText();
        return effectiveName.equals(name);
    }

    private String tableNameOf(LegacySqlParser.ExprContext expr) {
        String text = expr.getText();
        int dot = text.indexOf('.');
        return dot >= 0 ? text.substring(0, dot) : text;
    }

    private String renderPredicate(LegacySqlParser.PredicateContext ctx) {
        return renderExpr(ctx.expr(0)) + " " + ctx.compOp().getText() + " " + renderExpr(ctx.expr(1));
    }

    private String renderOrderList(LegacySqlParser.OrderListContext ctx) {
        List<String> items = new ArrayList<>();
        for (LegacySqlParser.OrderItemContext item : ctx.orderItem()) {
            StringBuilder s = new StringBuilder(renderExpr(item.expr()));
            if (item.ASC() != null) {
                s.append(" ASC");
            } else if (item.DESC() != null) {
                s.append(" DESC");
            }
            items.add(s.toString());
        }
        return String.join(", ", items);
    }

    private String renderExpr(LegacySqlParser.ExprContext ctx) {
        if (ctx instanceof LegacySqlParser.ArithExprContext arith) {
            return renderExpr(arith.expr(0)) + " " + arith.arithOp().getText() + " " + renderExpr(arith.expr(1));
        }
        if (ctx instanceof LegacySqlParser.ParenExprContext paren) {
            return "(" + renderExpr(paren.expr()) + ")";
        }
        if (ctx instanceof LegacySqlParser.FuncExprContext func) {
            return renderFunctionCall(func.functionCall());
        }
        if (ctx instanceof LegacySqlParser.ColumnExprContext col) {
            return col.getText();
        }
        if (ctx instanceof LegacySqlParser.LiteralExprContext lit) {
            return lit.getText();
        }
        throw new IllegalStateException("unhandled expr type: " + ctx.getClass());
    }

    private String renderFunctionCall(LegacySqlParser.FunctionCallContext ctx) {
        if (ctx.DECODE() != null) {
            List<LegacySqlParser.ExprContext> args = ctx.expr();
            StringBuilder sb = new StringBuilder("CASE ").append(renderExpr(args.get(0)));
            int i = 1;
            for (; i + 1 < args.size(); i += 2) {
                sb.append(" WHEN ").append(renderExpr(args.get(i))).append(" THEN ").append(renderExpr(args.get(i + 1)));
            }
            if (i < args.size()) {
                sb.append(" ELSE ").append(renderExpr(args.get(i)));
            }
            sb.append(" END");
            return sb.toString();
        }
        if (ctx.NVL() != null) {
            return "COALESCE(" + renderExpr(ctx.expr(0)) + ", " + renderExpr(ctx.expr(1)) + ")";
        }
        if (ctx.SYSDATE() != null) {
            return "CURRENT_DATE";
        }
        if (ctx.SUBSTR() != null) {
            List<String> args = new ArrayList<>();
            for (LegacySqlParser.ExprContext e : ctx.expr()) {
                args.add(renderExpr(e));
            }
            return "SUBSTR(" + String.join(", ", args) + ")";
        }
        if (ctx.UPPER() != null) {
            return "UPPER(" + renderExpr(ctx.expr(0)) + ")";
        }
        if (ctx.LOWER() != null) {
            return "LOWER(" + renderExpr(ctx.expr(0)) + ")";
        }
        if (ctx.TO_CHAR() != null) {
            // STRING format argument was already rejected in checkForUnsupportedToChar.
            return "CAST(" + renderExpr(ctx.expr(0)) + " AS VARCHAR)";
        }
        throw new IllegalStateException("unhandled function call: " + ctx.getText());
    }
}

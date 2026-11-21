package com.manasv.batchmigration.legacysql;

public class StatementOutcome {
    public int id;
    public String kind;
    public String legacySql;
    public String status; // "converted", "refused"
    public String ansiSql;
    public String refusalReason;
    public boolean proven;
    public String mismatchDetail;

    public StatementOutcome() {
    }
}

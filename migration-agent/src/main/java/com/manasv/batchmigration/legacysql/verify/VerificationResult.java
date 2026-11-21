package com.manasv.batchmigration.legacysql.verify;

import java.util.List;

public record VerificationResult(boolean equivalent, int sourceRowCount, int targetRowCount, String firstMismatch) {
    public static VerificationResult ok(int rowCount) {
        return new VerificationResult(true, rowCount, rowCount, null);
    }

    public static VerificationResult mismatch(int sourceRowCount, int targetRowCount, String detail) {
        return new VerificationResult(false, sourceRowCount, targetRowCount, detail);
    }
}

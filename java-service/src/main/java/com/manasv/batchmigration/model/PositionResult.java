package com.manasv.batchmigration.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The two derived fields the batch computes for a position, plus enough of
 * the source record to identify it in the differ's report.
 */
public record PositionResult(
        long recordId,
        String accountId,
        String isin,
        String currency,
        BigDecimal marketValue,
        LocalDate settleDate
) {
}

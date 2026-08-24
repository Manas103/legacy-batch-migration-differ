package com.manasv.batchmigration.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One parsed line of the fixed-width EOD position file. See docs/layout.md
 * for the field spec this was implemented against.
 */
public record PositionRecord(
        long recordId,
        String accountId,
        String isin,
        BigDecimal quantity,
        BigDecimal price,
        String currency,
        LocalDate tradeDate,
        int settleCycleDays
) {
}

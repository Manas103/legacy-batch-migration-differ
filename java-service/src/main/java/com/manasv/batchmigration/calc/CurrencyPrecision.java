package com.manasv.batchmigration.calc;

import java.util.Map;

/** Minor-unit decimal precision per currency, per docs/layout.md. */
public final class CurrencyPrecision {

    private static final Map<String, Integer> MINOR_UNITS = Map.of(
            "USD", 2,
            "EUR", 2,
            "GBP", 2,
            "JPY", 0
    );

    private CurrencyPrecision() {
    }

    public static int minorUnits(String currency) {
        Integer scale = MINOR_UNITS.get(currency);
        if (scale == null) {
            throw new IllegalArgumentException("unsupported currency: " + currency);
        }
        return scale;
    }
}

package com.manasv.batchmigration.calc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 15 cases for {@link PositionValuationService#computeMarketValue}: ordinary
 * rounding in each currency present in the dataset, the exact-half-cent tie
 * boundary (HALF_UP always rounds away from zero, including through a carry
 * into the next digit) for both a 2-decimal and a 0-decimal currency, and
 * zero, negative, and large-magnitude quantities. HALF_UP is the fixed
 * behavior; see the class Javadoc on {@code computeMarketValue} for why this
 * was originally HALF_EVEN and what the parallel-run differ found.
 */
class PositionValuationServiceMarketValueTest {

    private final PositionValuationService service = new PositionValuationService(new BusinessDayCalendar());

    @Test
    void usdRoundsDownWhenTheThirdDecimalIsBelowFive() {
        BigDecimal value = service.computeMarketValue(new BigDecimal("50.0000"), new BigDecimal("10.123456"), "USD");
        assertEquals(new BigDecimal("506.17"), value);
    }

    @Test
    void usdRoundsUpWhenTheFractionBeyondTwoDecimalsExceedsHalfWithoutBeingATie() {
        BigDecimal value = service.computeMarketValue(new BigDecimal("1.0000"), new BigDecimal("10.127500"), "USD");
        assertEquals(new BigDecimal("10.13"), value);
    }

    @Test
    void usdExactHalfTieRoundsAwayFromZero() {
        BigDecimal value = service.computeMarketValue(new BigDecimal("1.0000"), new BigDecimal("10.125000"), "USD");
        assertEquals(new BigDecimal("10.13"), value);
    }

    @Test
    void usdExactHalfTieCarryPropagatesThroughRepeatedNines() {
        BigDecimal value = service.computeMarketValue(new BigDecimal("1.0000"), new BigDecimal("19.995000"), "USD");
        assertEquals(new BigDecimal("20.00"), value);
    }

    @Test
    void jpyHasZeroDecimalPlacesAndRoundsOnOrdinaryFractions() {
        BigDecimal value = service.computeMarketValue(new BigDecimal("100.0000"), new BigDecimal("55.678900"), "JPY");
        assertEquals(new BigDecimal("5568"), value);
    }

    @Test
    void jpyExactHalfTieRoundsAwayFromZero() {
        BigDecimal value = service.computeMarketValue(new BigDecimal("1.0000"), new BigDecimal("100.500000"), "JPY");
        assertEquals(new BigDecimal("101"), value);
    }

    @Test
    void jpyExactHalfTieCarryPropagatesThroughRepeatedNines() {
        BigDecimal value = service.computeMarketValue(new BigDecimal("1.0000"), new BigDecimal("999.500000"), "JPY");
        assertEquals(new BigDecimal("1000"), value);
    }

    @Test
    void eurRoundsToTwoDecimalsWithNoRemainder() {
        BigDecimal value = service.computeMarketValue(new BigDecimal("20.0000"), new BigDecimal("3.334000"), "EUR");
        assertEquals(new BigDecimal("66.68"), value);
    }

    @Test
    void gbpRoundsDownWhenTheRemainderIsNegligible() {
        BigDecimal value = service.computeMarketValue(new BigDecimal("7.0000"), new BigDecimal("142.857143"), "GBP");
        assertEquals(new BigDecimal("1000.00"), value);
    }

    @Test
    void zeroQuantityYieldsZeroMarketValue() {
        BigDecimal value = service.computeMarketValue(new BigDecimal("0.0000"), new BigDecimal("999.999999"), "USD");
        assertEquals(new BigDecimal("0.00"), value);
    }

    @Test
    void negativeQuantityShortPositionRoundsTowardTheNearerCent() {
        BigDecimal value = service.computeMarketValue(new BigDecimal("-3.0000"), new BigDecimal("10.004000"), "USD");
        assertEquals(new BigDecimal("-30.01"), value);
    }

    @Test
    void negativeExactHalfTieRoundsAwayFromZeroToTheMoreNegativeValue() {
        BigDecimal value = service.computeMarketValue(new BigDecimal("-1.0000"), new BigDecimal("10.125000"), "USD");
        assertEquals(new BigDecimal("-10.13"), value);
    }

    @Test
    void negativeSmallMagnitudeRoundsToPlainZeroNotNegativeZero() {
        // BigDecimal has no signed-zero representation, unlike Python's
        // decimal.Decimal (see legacy_batch.py's compute_market_value for
        // the fix made on that side after the differ found this).
        BigDecimal value = service.computeMarketValue(new BigDecimal("-1.7194"), new BigDecimal("0.276112"), "JPY");
        assertEquals(new BigDecimal("0"), value);
        assertEquals(0, value.signum());
    }

    @Test
    void largeQuantityAndPriceKeepFullPrecisionBeforeRounding() {
        BigDecimal value = service.computeMarketValue(new BigDecimal("500000.0000"), new BigDecimal("9999.999999"), "USD");
        assertEquals(new BigDecimal("4999999999.50"), value);
    }

    @Test
    void veryFinePriceFractionStillRoundsCorrectly() {
        BigDecimal value = service.computeMarketValue(new BigDecimal("333333.0000"), new BigDecimal("0.000003"), "USD");
        assertEquals(new BigDecimal("1.00"), value);
    }
}

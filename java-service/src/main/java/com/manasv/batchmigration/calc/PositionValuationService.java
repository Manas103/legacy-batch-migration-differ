package com.manasv.batchmigration.calc;

import com.manasv.batchmigration.model.PositionRecord;
import com.manasv.batchmigration.model.PositionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Core business logic for the position batch: market value and contractual
 * settlement date. This is the class the 41 JUnit cases in this repository
 * target directly, with no Spring context required.
 */
@Service
public class PositionValuationService {

    private final BusinessDayCalendar calendar;

    @Autowired
    public PositionValuationService(BusinessDayCalendar calendar) {
        this.calendar = calendar;
    }

    /**
     * quantity * price, rounded to the currency's minor-unit precision.
     * Rounded with {@link RoundingMode#HALF_UP}, to match the legacy
     * Python batch's rounding convention. This was not the first choice
     * made here: this class originally rounded with {@code HALF_EVEN} (the
     * mode {@link java.math.MathContext#DECIMAL64}/{@code DECIMAL128} use,
     * and a defensible independent choice for a from-spec reimplementation),
     * and the parallel-run differ genuinely caught the disagreement with
     * the legacy side on exact half-cent boundaries before this was
     * changed. See the README's findings section for that run. Matching the
     * legacy rounding convention, rather than the other way around, is the
     * deliberate decision for a migration: changing which way a ledger
     * rounds mid-cutover is a business decision that needs its own sign-off,
     * not a side effect of a rewrite.
     */
    public BigDecimal computeMarketValue(BigDecimal quantity, BigDecimal price, String currency) {
        if (quantity == null) {
            throw new IllegalArgumentException("quantity must not be null");
        }
        if (price == null) {
            throw new IllegalArgumentException("price must not be null");
        }
        int scale = CurrencyPrecision.minorUnits(currency);
        return quantity.multiply(price).setScale(scale, RoundingMode.HALF_UP);
    }

    /** trade date plus settleCycleDays business days. */
    public LocalDate computeSettleDate(LocalDate tradeDate, int settleCycleDays) {
        return calendar.addBusinessDays(tradeDate, settleCycleDays);
    }

    public PositionResult evaluate(PositionRecord record) {
        BigDecimal marketValue = computeMarketValue(record.quantity(), record.price(), record.currency());
        LocalDate settleDate = computeSettleDate(record.tradeDate(), record.settleCycleDays());
        return new PositionResult(
                record.recordId(), record.accountId(), record.isin(), record.currency(), marketValue, settleDate);
    }
}

package com.manasv.batchmigration.calc;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 11 cases for {@link PositionValuationService#computeSettleDate}: plain
 * T+N cycles, a weekend crossing, each of the three holidays including
 * their Saturday/Sunday observance shift, a leap day, a year boundary, a
 * month-end roll, and one test that pins the current (documented) behavior
 * for a trade date that itself falls on a weekend.
 */
class PositionValuationServiceSettleDateTest {

    private final PositionValuationService service = new PositionValuationService(new BusinessDayCalendar());

    @Test
    void settleCycleZeroOnABusinessDayReturnsTheSameDate() {
        assertEquals(LocalDate.of(2023, 6, 14), service.computeSettleDate(LocalDate.of(2023, 6, 14), 0));
    }

    @Test
    void settleCycleOneMovesToTheNextBusinessDay() {
        assertEquals(LocalDate.of(2023, 6, 15), service.computeSettleDate(LocalDate.of(2023, 6, 14), 1));
    }

    @Test
    void settleCycleTwoOnAnOrdinaryWeek() {
        assertEquals(LocalDate.of(2023, 6, 16), service.computeSettleDate(LocalDate.of(2023, 6, 14), 2));
    }

    @Test
    void settleCycleThreeCrossesAPlainWeekend() {
        assertEquals(LocalDate.of(2023, 6, 20), service.computeSettleDate(LocalDate.of(2023, 6, 15), 3));
    }

    @Test
    void settleDateSkipsNewYearsDayFallingOnAWeekday() {
        // Trade Fri 2018-12-28, T+2: Mon 12-31 (1), Tue Jan 1 2019 is the
        // holiday itself (no shift needed, it's a weekday), Wed Jan 2 (2).
        assertEquals(LocalDate.of(2019, 1, 2), service.computeSettleDate(LocalDate.of(2018, 12, 28), 2));
    }

    @Test
    void settleDateSkipsIndependenceDayObservedOnTheFridayBeforeASaturdayHoliday() {
        // Jul 4 2020 is a Saturday, observed Friday Jul 3.
        assertEquals(LocalDate.of(2020, 7, 6), service.computeSettleDate(LocalDate.of(2020, 6, 30), 3));
    }

    @Test
    void settleDateSkipsChristmasObservedOnTheMondayAfterASundayHoliday() {
        // Dec 25 2022 is a Sunday, observed Monday Dec 26.
        assertEquals(LocalDate.of(2022, 12, 28), service.computeSettleDate(LocalDate.of(2022, 12, 22), 3));
    }

    @Test
    void settleDateCrossesALeapDayThatFallsOnAWeekend() {
        // Feb 29 2020 is a Saturday: ordinary weekend exclusion, not a holiday.
        assertEquals(LocalDate.of(2020, 3, 2), service.computeSettleDate(LocalDate.of(2020, 2, 27), 2));
    }

    @Test
    void settleDateCrossesTheYearBoundaryThroughAnObservedNewYearsHoliday() {
        // Jan 1 2022 is a Saturday, observed Friday Dec 31 2021.
        assertEquals(LocalDate.of(2022, 1, 4), service.computeSettleDate(LocalDate.of(2021, 12, 29), 3));
    }

    @Test
    void settleDateRollsAcrossAMonthEnd() {
        assertEquals(LocalDate.of(2022, 5, 2), service.computeSettleDate(LocalDate.of(2022, 4, 29), 1));
    }

    @Test
    void settleDateWhenTradeDateItselfFallsOnAWeekendNormalizesToTheNextBusinessDayFirst() {
        // 2023-04-01 is a Saturday. Normalize to the next business day
        // (Monday 2023-04-03), then add 2 business days: Tue 04-04 (1),
        // Wed 04-05 (2). This class originally started counting immediately
        // from the raw Saturday instead (giving 2023-04-04), which is
        // exactly the divergence the parallel-run differ caught against the
        // legacy side; see docs/layout.md and the README's findings section.
        assertEquals(LocalDate.of(2023, 4, 5), service.computeSettleDate(LocalDate.of(2023, 4, 1), 2));
    }
}

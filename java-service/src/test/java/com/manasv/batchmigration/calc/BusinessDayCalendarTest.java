package com.manasv.batchmigration.calc;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 15 cases covering weekend detection, the three fixed holidays (including
 * Saturday/Sunday observance, including the cross-year-boundary case), leap
 * day, and multi-day business-day walks.
 */
class BusinessDayCalendarTest {

    private final BusinessDayCalendar calendar = new BusinessDayCalendar();

    @Test
    void ordinaryWeekdayIsBusinessDay() {
        assertTrue(calendar.isBusinessDay(LocalDate.of(2023, 6, 14))); // Wednesday
    }

    @Test
    void saturdayIsNotBusinessDay() {
        assertFalse(calendar.isBusinessDay(LocalDate.of(2023, 6, 17)));
    }

    @Test
    void sundayIsNotBusinessDay() {
        assertFalse(calendar.isBusinessDay(LocalDate.of(2023, 6, 18)));
    }

    @Test
    void newYearsDayOnWeekdayIsHoliday() {
        // Jan 1 2019 is a Tuesday, no observance shift needed.
        assertFalse(calendar.isBusinessDay(LocalDate.of(2019, 1, 1)));
    }

    @Test
    void newYearsDayFallingOnSaturdayIsObservedFridayBefore() {
        // Jan 1 2022 is a Saturday.
        assertFalse(calendar.isBusinessDay(LocalDate.of(2021, 12, 31))); // observed Friday
        assertTrue(calendar.isBusinessDay(LocalDate.of(2022, 1, 3)));   // following Monday is ordinary
    }

    @Test
    void newYearsDayFallingOnSundayIsObservedMondayAfter() {
        // Jan 1 2023 is a Sunday.
        assertFalse(calendar.isBusinessDay(LocalDate.of(2023, 1, 2))); // observed Monday
        assertTrue(calendar.isBusinessDay(LocalDate.of(2022, 12, 30))); // preceding Friday is ordinary
    }

    @Test
    void independenceDayFallingOnSaturdayIsObservedFridayBefore() {
        // Jul 4 2020 is a Saturday.
        assertFalse(calendar.isBusinessDay(LocalDate.of(2020, 7, 3)));
    }

    @Test
    void independenceDayFallingOnSundayIsObservedMondayAfter() {
        // Jul 4 2021 is a Sunday.
        assertFalse(calendar.isBusinessDay(LocalDate.of(2021, 7, 5)));
    }

    @Test
    void christmasFallingOnSaturdayIsObservedFridayBefore() {
        // Dec 25 2021 is a Saturday.
        assertFalse(calendar.isBusinessDay(LocalDate.of(2021, 12, 24)));
    }

    @Test
    void christmasFallingOnSundayIsObservedMondayAfter() {
        // Dec 25 2022 is a Sunday.
        assertFalse(calendar.isBusinessDay(LocalDate.of(2022, 12, 26)));
    }

    @Test
    void leapDayThatFallsOnAWeekendIsNotABusinessDayButNotBecauseItsAHoliday() {
        // Feb 29 2020 is a Saturday, ordinary weekend exclusion, not the holiday rule.
        assertFalse(calendar.isBusinessDay(LocalDate.of(2020, 2, 29)));
    }

    @Test
    void leapDayThatFallsOnAWeekdayIsAnOrdinaryBusinessDay() {
        // Feb 29 2024 is a Thursday.
        assertTrue(calendar.isBusinessDay(LocalDate.of(2024, 2, 29)));
    }

    @Test
    void addBusinessDaysSkipsAPlainWeekend() {
        // Friday 2023-06-16 + 1 business day -> Monday 2023-06-19.
        assertEquals(LocalDate.of(2023, 6, 19), calendar.addBusinessDays(LocalDate.of(2023, 6, 16), 1));
    }

    @Test
    void addBusinessDaysSkipsAWeekendAndAnObservedHolidayTogether() {
        // Thursday 2021-12-23 + 3 business days: Fri 24 (observed Christmas, skipped),
        // Sat/Sun skipped, Mon 27 (1), Tue 28 (2), Wed 29 (3) -> 2021-12-29.
        assertEquals(LocalDate.of(2021, 12, 29), calendar.addBusinessDays(LocalDate.of(2021, 12, 23), 3));
    }

    @Test
    void addBusinessDaysCrossesALeapDayNormally() {
        // Wednesday 2024-02-28 + 2 business days: Thu 29 (1, leap day, ordinary weekday), Fri Mar 1 (2).
        assertEquals(LocalDate.of(2024, 3, 1), calendar.addBusinessDays(LocalDate.of(2024, 2, 28), 2));
    }
}

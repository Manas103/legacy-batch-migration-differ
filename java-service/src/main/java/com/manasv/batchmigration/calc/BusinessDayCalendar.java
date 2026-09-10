package com.manasv.batchmigration.calc;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * US-style fixed holiday calendar (New Year's Day, Independence Day,
 * Christmas Day) with Saturday/Sunday observance, plus weekend exclusion,
 * per docs/layout.md.
 */
@Component
public class BusinessDayCalendar {

    private final Map<Integer, Set<LocalDate>> holidaysByYear = new HashMap<>();

    private static LocalDate observed(LocalDate holiday) {
        DayOfWeek dow = holiday.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY) {
            return holiday.minusDays(1);
        }
        if (dow == DayOfWeek.SUNDAY) {
            return holiday.plusDays(1);
        }
        return holiday;
    }

    private static Set<LocalDate> fixedHolidaysFor(int year) {
        return Set.of(
                observed(LocalDate.of(year, Month.JANUARY, 1)),
                observed(LocalDate.of(year, Month.JULY, 4)),
                observed(LocalDate.of(year, Month.DECEMBER, 25))
        );
    }

    private Set<LocalDate> holidaysFor(int year) {
        return holidaysByYear.computeIfAbsent(year, y -> {
            Set<LocalDate> days = new HashSet<>(fixedHolidaysFor(y));
            // New Year's observance can shift backward into December of the
            // previous year when it falls on a Saturday; a lookup keyed by
            // that December date must still find it.
            LocalDate newYearsDayOfNextYear = LocalDate.of(y + 1, Month.JANUARY, 1);
            if (newYearsDayOfNextYear.getDayOfWeek() == DayOfWeek.SATURDAY) {
                days.add(newYearsDayOfNextYear.minusDays(1));
            }
            return Set.copyOf(days);
        });
    }

    public boolean isBusinessDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        return !holidaysFor(date.getYear()).contains(date);
    }

    /**
     * If {@code date} is already a business day, return it unchanged.
     * Otherwise roll forward to the next one.
     */
    public LocalDate nextBusinessDay(LocalDate date) {
        LocalDate d = date;
        while (!isBusinessDay(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    /**
     * Add {@code businessDays} business days to {@code start}, skipping
     * weekends and holidays.
     *
     * <p>This originally walked forward from {@code start} directly with no
     * special case for a {@code start} that is not itself a business day, a
     * natural implementation gap for a from-spec reimplementation (every
     * trade date this batch had ever seen in a hand-checked sample was
     * already a business day, so the gap was invisible until the full
     * 250,000-record parallel run). The legacy side normalizes a non
     * business day start to the next business day before counting, on the
     * theory that a trade booked on a weekend or holiday is a late or
     * erroneous entry and its settlement clock should start when the market
     * is next open. The differ caught the one class of record where the two
     * disagreed; this now normalizes the same way. See the README's
     * findings section.
     */
    public LocalDate addBusinessDays(LocalDate start, int businessDays) {
        LocalDate date = nextBusinessDay(start);
        return advanceByBusinessDays(date, businessDays);
    }

    /**
     * Advance from {@code from} by {@code businessDays} business days,
     * skipping weekends and holidays.
     */
    private LocalDate advanceByBusinessDays(LocalDate from, int businessDays) {
        LocalDate date = from;
        int remaining = businessDays;
        while (remaining > 0) {
            date = date.plusDays(1);
            if (isBusinessDay(date)) {
                remaining--;
            }
        }
        return date;
    }
}

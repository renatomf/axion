package dev.axion.core.shared;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Business dates in {@code America/Sao_Paulo}, where money settles: a payment at 22:30 there on
 * March 9 is 01:30 UTC on March 10, yet belongs to March 9. Business days exclude weekends and
 * national bank holidays.
 */
public final class BusinessCalendar {

    // No daylight saving time in Brazil since 2019.
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private BusinessCalendar() {}

    public static LocalDate businessDateOf(Instant instant) {
        return LocalDate.ofInstant(instant, ZONE);
    }

    public static LocalDate today(Clock clock) {
        return businessDateOf(clock.instant());
    }

    public static boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        return !weekend && !BankHolidays.isHoliday(date);
    }

    public static LocalDate onOrAfter(LocalDate date) {
        LocalDate current = date;
        while (!isBusinessDay(current)) {
            current = current.plusDays(1);
        }
        return current;
    }

    /**
     * The date {@code days} business days later.
     *
     * @throws IllegalArgumentException if {@code days} is negative
     */
    public static LocalDate plusBusinessDays(LocalDate date, int days) {
        if (days < 0) {
            throw new IllegalArgumentException("Business days must not be negative: " + days);
        }
        LocalDate current = date;
        for (int counted = 0; counted < days; counted++) {
            current = onOrAfter(current.plusDays(1));
        }
        return current;
    }
}

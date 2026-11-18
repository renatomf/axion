package dev.finaxis.core.shared;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Business dates and business days, in the time zone where the money settles.
 *
 * <p>Instants are stored in UTC. A <em>business date</em> is the calendar date of an instant in
 * {@code America/Sao_Paulo}: a payment at 22:30 in São Paulo on March 9 is 01:30 UTC on March 10,
 * yet it belongs to March 9. A <em>business day</em> is a weekday that is not a national bank
 * holiday ({@link BankHolidays}).
 */
public final class BusinessCalendar {

    /** The time zone of business dates. Brazil has had no daylight saving time since 2019. */
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private BusinessCalendar() {}

    /** The business date of an instant. */
    public static LocalDate businessDateOf(Instant instant) {
        return LocalDate.ofInstant(instant, ZONE);
    }

    /** Today's business date, by the given clock. */
    public static LocalDate today(Clock clock) {
        return businessDateOf(clock.instant());
    }

    /** Whether banks settle on the date: a weekday that is not a bank holiday. */
    public static boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        return !weekend && !BankHolidays.isHoliday(date);
    }

    /**
     * The date itself if it is a business day, otherwise the next business day; for example, a due
     * date that falls on a holiday moves to the next business day.
     */
    public static LocalDate onOrAfter(LocalDate date) {
        LocalDate current = date;
        while (!isBusinessDay(current)) {
            current = current.plusDays(1);
        }
        return current;
    }

    /**
     * The date that is the given number of business days after the date; for example, two business
     * days after a Friday is the Tuesday, or the Wednesday if the Monday is a holiday.
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

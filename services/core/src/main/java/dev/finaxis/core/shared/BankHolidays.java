package dev.finaxis.core.shared;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The national bank holidays in Brazil: the days banks do not settle, as published in the ANBIMA
 * national holiday calendar.
 *
 * <p>Most are fixed dates. Four move with Easter: Carnival Monday and Tuesday (48 and 47 days
 * before), Good Friday (2 days before) and Corpus Christi (60 days after). Black Consciousness Day
 * (November 20) is a national holiday from 2024 on (Law 14,759/2023).
 *
 * <p>State and municipal holidays are not included: settlement follows the national calendar. Pix
 * itself runs every day; business days matter for due dates and payout schedules.
 */
public final class BankHolidays {

    private static final List<MonthDay> FIXED = List.of(
            MonthDay.of(1, 1), // New Year's Day (Confraternização Universal)
            MonthDay.of(4, 21), // Tiradentes
            MonthDay.of(5, 1), // Labour Day
            MonthDay.of(9, 7), // Independence Day
            MonthDay.of(10, 12), // Our Lady of Aparecida
            MonthDay.of(11, 2), // All Souls' Day (Finados)
            MonthDay.of(11, 15), // Proclamation of the Republic
            MonthDay.of(12, 25)); // Christmas

    private static final MonthDay BLACK_CONSCIOUSNESS_DAY = MonthDay.of(11, 20);
    private static final int FIRST_YEAR_OF_BLACK_CONSCIOUSNESS_DAY = 2024;

    private BankHolidays() {}

    /** Whether the date is a national bank holiday. */
    public static boolean isHoliday(LocalDate date) {
        return of(date.getYear()).contains(date);
    }

    /** Every national bank holiday of the year, in date order. */
    public static Set<LocalDate> of(int year) {
        Set<LocalDate> holidays = new TreeSet<>();
        FIXED.forEach(day -> holidays.add(day.atYear(year)));
        if (year >= FIRST_YEAR_OF_BLACK_CONSCIOUSNESS_DAY) {
            holidays.add(BLACK_CONSCIOUSNESS_DAY.atYear(year));
        }
        LocalDate easter = easterSunday(year);
        holidays.add(easter.minusDays(48)); // Carnival Monday
        holidays.add(easter.minusDays(47)); // Carnival Tuesday
        holidays.add(easter.minusDays(2)); // Good Friday
        holidays.add(easter.plusDays(60)); // Corpus Christi
        return Collections.unmodifiableSet(holidays);
    }

    /**
     * Easter Sunday in the Gregorian calendar, by the anonymous Gregorian algorithm (Meeus/Jones/
     * Butcher). It finds the first Sunday after the ecclesiastical full moon on or after March 21,
     * using only integer arithmetic.
     */
    static LocalDate easterSunday(int year) {
        int a = year % 19; // position in the 19-year lunar cycle
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30; // days from March 21 to the full moon, roughly
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7; // days from the full moon to the next Sunday
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(year, month, day);
    }
}

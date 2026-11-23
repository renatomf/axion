package dev.axion.core.shared;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * National bank holidays, as in ANBIMA's calendar: fixed dates, four that move with Easter, and
 * Black Consciousness Day from 2024 (Law 14,759/2023). Settlement follows the national calendar
 * only.
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

    public static boolean isHoliday(LocalDate date) {
        return of(date.getYear()).contains(date);
    }

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

    // Anonymous Gregorian algorithm (Meeus/Jones/Butcher), integer arithmetic only.
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

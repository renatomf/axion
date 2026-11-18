package dev.finaxis.core.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BankHolidaysTest {

    @ParameterizedTest
    @CsvSource({
        "2000, 2000-04-23",
        "2019, 2019-04-21",
        "2024, 2024-03-31",
        "2025, 2025-04-20",
        "2026, 2026-04-05",
        "2027, 2027-03-28",
        "2038, 2038-04-25"
    })
    void findsEasterSunday(int year, LocalDate easter) {
        assertThat(BankHolidays.easterSunday(year)).isEqualTo(easter);
    }

    @Test
    void listsThe2026CalendarPublishedByAnbima() {
        assertThat(BankHolidays.of(2026))
                .containsExactly(
                        LocalDate.parse("2026-01-01"),
                        LocalDate.parse("2026-02-16"),
                        LocalDate.parse("2026-02-17"),
                        LocalDate.parse("2026-04-03"),
                        LocalDate.parse("2026-04-21"),
                        LocalDate.parse("2026-05-01"),
                        LocalDate.parse("2026-06-04"),
                        LocalDate.parse("2026-09-07"),
                        LocalDate.parse("2026-10-12"),
                        LocalDate.parse("2026-11-02"),
                        LocalDate.parse("2026-11-15"),
                        LocalDate.parse("2026-11-20"),
                        LocalDate.parse("2026-12-25"));
    }

    @Test
    void blackConsciousnessDayIsAHolidayFrom2024On() {
        assertThat(BankHolidays.isHoliday(LocalDate.parse("2023-11-20"))).isFalse();
        assertThat(BankHolidays.isHoliday(LocalDate.parse("2024-11-20"))).isTrue();
    }

    @Test
    void anOrdinaryDayIsNotAHoliday() {
        assertThat(BankHolidays.isHoliday(LocalDate.parse("2026-11-18"))).isFalse();
    }
}

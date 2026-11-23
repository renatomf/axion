package dev.axion.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BusinessCalendarTest {

    // Wednesday 2026-11-18 at 01:30 UTC is still Tuesday evening in São Paulo (UTC-3).
    private static final Instant LATE_TUESDAY_IN_SAO_PAULO = Instant.parse("2026-11-18T01:30:00Z");

    @Test
    void theBusinessDateIsTheDateInSaoPaulo() {
        assertThat(BusinessCalendar.businessDateOf(LATE_TUESDAY_IN_SAO_PAULO)).isEqualTo(date("2026-11-17"));
    }

    @Test
    void todayReadsTheClockInSaoPaulo() {
        Clock clock = Clock.fixed(LATE_TUESDAY_IN_SAO_PAULO, ZoneOffset.UTC);

        assertThat(BusinessCalendar.today(clock)).isEqualTo(date("2026-11-17"));
    }

    @Test
    void weekendsAndHolidaysAreNotBusinessDays() {
        assertThat(BusinessCalendar.isBusinessDay(date("2026-11-18"))).isTrue(); // Wednesday
        assertThat(BusinessCalendar.isBusinessDay(date("2026-11-21"))).isFalse(); // Saturday
        assertThat(BusinessCalendar.isBusinessDay(date("2026-11-22"))).isFalse(); // Sunday
        assertThat(BusinessCalendar.isBusinessDay(date("2026-11-20"))).isFalse(); // Friday, holiday
    }

    @Test
    void aBusinessDayStaysWhereItIs() {
        assertThat(BusinessCalendar.onOrAfter(date("2026-11-18"))).isEqualTo(date("2026-11-18"));
    }

    @Test
    void aHolidayBeforeAWeekendMovesToMonday() {
        assertThat(BusinessCalendar.onOrAfter(date("2026-11-20"))).isEqualTo(date("2026-11-23"));
    }

    @Test
    void carnivalSkipsMondayAndTuesday() {
        // Friday 2026-02-13, then Carnival on Monday 16 and Tuesday 17.
        assertThat(BusinessCalendar.plusBusinessDays(date("2026-02-13"), 1)).isEqualTo(date("2026-02-18"));
    }

    @Test
    void countsBusinessDaysAcrossAWeekend() {
        // Thursday 2026-11-12: Friday 13, then Monday 16 and Tuesday 17.
        assertThat(BusinessCalendar.plusBusinessDays(date("2026-11-12"), 3)).isEqualTo(date("2026-11-17"));
    }

    @Test
    void zeroBusinessDaysIsTheSameDate() {
        assertThat(BusinessCalendar.plusBusinessDays(date("2026-11-21"), 0)).isEqualTo(date("2026-11-21"));
    }

    @Test
    void rejectsNegativeBusinessDays() {
        assertThatThrownBy(() -> BusinessCalendar.plusBusinessDays(date("2026-11-18"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LocalDate date(String iso) {
        return LocalDate.parse(iso);
    }
}

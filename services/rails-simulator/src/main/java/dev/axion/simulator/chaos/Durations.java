package dev.axion.simulator.chaos;

import java.math.BigDecimal;
import java.time.Duration;

/** Durations as the operator API writes them: ISO 8601 in seconds, to the millisecond, such as {@code PT90.5S}. */
public final class Durations {

    private Durations() {}

    /** {@link Duration#toString()} would write minutes and hours, which the API's format has no place for. */
    public static String inSeconds(Duration duration) {
        return "PT"
                + BigDecimal.valueOf(duration.toMillis(), 3)
                        .stripTrailingZeros()
                        .toPlainString() + "S";
    }
}

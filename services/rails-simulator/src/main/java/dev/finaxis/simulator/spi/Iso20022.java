package dev.finaxis.simulator.spi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * How the simulator writes amounts and times, in its messages and its API alike (rails simulator design,
 * section 5.1).
 */
public final class Iso20022 {

    private static final DateTimeFormatter MILLISECONDS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Iso20022() {}

    /** An amount in reais: a decimal string with two fraction digits, never a JSON number. */
    public record Amount(String amount, String currency) {

        public static Amount ofMinor(long minor) {
            return new Amount(BigDecimal.valueOf(minor, 2).toPlainString(), "BRL");
        }
    }

    /** A time in UTC with milliseconds, always three fraction digits. */
    public static String dateTime(Instant instant) {
        return MILLISECONDS.format(instant);
    }
}

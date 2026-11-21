package dev.finaxis.simulator.spi;

import java.util.regex.Pattern;

/**
 * The eight-digit code the Central Bank assigns to each payment institution. The simulator uses
 * fictitious codes only (rails simulator design, section 3.2).
 *
 * @param digits the eight digits
 */
public record Ispb(String digits) {

    private static final Pattern DIGITS = Pattern.compile("[0-9]{8}");

    public Ispb {
        if (!DIGITS.matcher(digits).matches()) {
            throw new IllegalArgumentException("An ISPB has eight digits: " + digits);
        }
    }

    @Override
    public String toString() {
        return digits;
    }
}

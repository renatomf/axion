package dev.axion.core.shared;

import java.util.regex.Pattern;

/**
 * A person's taxpayer number: 9 digits and 2 check digits. Personal data, so {@link #toString}
 * masks it.
 *
 * @param digits the 11 digits, without punctuation
 */
public record Cpf(String digits) {

    private static final Pattern DIGITS = Pattern.compile("[0-9]{11}");
    private static final Pattern FORMATTED = Pattern.compile("[0-9]{3}\\.[0-9]{3}\\.[0-9]{3}-[0-9]{2}");
    private static final int MAX_WEIGHT = 11;

    public Cpf {
        if (!DIGITS.matcher(digits).matches() || !CheckDigits.isValid(digits, MAX_WEIGHT)) {
            // The input is not echoed: it may be a real person's CPF.
            throw new IllegalArgumentException("Not a valid CPF");
        }
    }

    /**
     * Accepts {@code 52998224725} or {@code 529.982.247-25}.
     *
     * @throws IllegalArgumentException if the text is not a valid CPF
     */
    public static Cpf parse(String text) {
        if (FORMATTED.matcher(text).matches()) {
            return new Cpf(text.replace(".", "").replace("-", ""));
        }
        return new Cpf(text);
    }

    public String formatted() {
        return digits.substring(0, 3) + "." + digits.substring(3, 6) + "." + digits.substring(6, 9) + "-"
                + digits.substring(9);
    }

    @Override
    public String toString() {
        return "***." + digits.substring(3, 6) + "." + digits.substring(6, 9) + "-**";
    }
}

package dev.finaxis.core.shared;

import java.util.regex.Pattern;

/**
 * A CPF, the taxpayer number of a person in Brazil: 9 digits followed by 2 check digits.
 *
 * <p>A CPF is personal data. {@link #toString} masks it, as public records do ({@code
 * ***.456.789-**}), so a CPF written to a log or an exception message does not leak; the full
 * number is read with {@link #digits} or {@link #formatted}, only where it is needed.
 *
 * @param digits the 11 digits, without punctuation
 */
public record Cpf(String digits) {

    private static final Pattern DIGITS = Pattern.compile("[0-9]{11}");
    private static final Pattern FORMATTED = Pattern.compile("[0-9]{3}\\.[0-9]{3}\\.[0-9]{3}-[0-9]{2}");
    private static final int MAX_WEIGHT = 11;

    /**
     * Rejects anything but 11 digits with correct check digits. Numbers of one repeated digit, such
     * as {@code 111.111.111-11}, have correct check digits but are never issued, and are rejected too.
     */
    public Cpf {
        if (!DIGITS.matcher(digits).matches() || isOneRepeatedDigit(digits) || !hasValidCheckDigits(digits)) {
            // The input is not echoed: it may be a real person's CPF.
            throw new IllegalArgumentException("Not a valid CPF");
        }
    }

    /**
     * Parses a CPF written as 11 digits ({@code 52998224725}) or with the usual punctuation ({@code
     * 529.982.247-25}). Spaces and any other form are rejected.
     *
     * @throws IllegalArgumentException if the text is not a valid CPF
     */
    public static Cpf parse(String text) {
        if (FORMATTED.matcher(text).matches()) {
            return new Cpf(text.replace(".", "").replace("-", ""));
        }
        return new Cpf(text);
    }

    /** The CPF with its usual punctuation, such as {@code 529.982.247-25}. */
    public String formatted() {
        return digits.substring(0, 3) + "." + digits.substring(3, 6) + "." + digits.substring(6, 9) + "-"
                + digits.substring(9);
    }

    /** The masked CPF, such as {@code ***.982.247-**}: the first 3 and the check digits are hidden. */
    @Override
    public String toString() {
        return "***." + digits.substring(3, 6) + "." + digits.substring(6, 9) + "-**";
    }

    private static boolean isOneRepeatedDigit(String digits) {
        return digits.chars().allMatch(c -> c == digits.charAt(0));
    }

    private static boolean hasValidCheckDigits(String digits) {
        int[] values = digits.chars().map(c -> c - '0').toArray();
        return CheckDigits.mod11(values, 9, MAX_WEIGHT) == values[9]
                && CheckDigits.mod11(values, 10, MAX_WEIGHT) == values[10];
    }
}

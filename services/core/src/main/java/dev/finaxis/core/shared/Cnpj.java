package dev.finaxis.core.shared;

import java.util.regex.Pattern;

/**
 * A CNPJ, the taxpayer number of a company in Brazil: 12 characters (8 for the company and 4 for
 * the branch) followed by 2 check digits.
 *
 * <p>From July 2026 the Receita Federal issues alphanumeric CNPJs (Normative Instruction RFB
 * 2,229/2024): the first 12 characters may be digits or uppercase letters, and the check digits
 * stay numeric. Numbers already issued keep their digits, which are a valid case of the same
 * format. Each character counts as its ASCII code minus 48, so digits keep their value and {@code
 * A} to {@code Z} count as 17 to 42.
 *
 * <p>{@link #toString} masks the number ({@code **.222.333/0001-**}), as {@link Cpf} does: the CNPJ
 * of a sole trader identifies a person.
 *
 * @param value the 14 characters, without punctuation, such as {@code 12ABC34501DE35}
 */
public record Cnpj(String value) {

    private static final Pattern PLAIN = Pattern.compile("[0-9A-Z]{12}[0-9]{2}");
    private static final Pattern FORMATTED =
            Pattern.compile("[0-9A-Z]{2}\\.[0-9A-Z]{3}\\.[0-9A-Z]{3}/[0-9A-Z]{4}-[0-9]{2}");
    private static final int MAX_WEIGHT = 9;

    /**
     * Rejects anything but 12 digits or uppercase letters and 2 correct check digits. Numbers of one
     * repeated character, such as {@code 00.000.000/0000-00}, are rejected too.
     */
    public Cnpj {
        if (!PLAIN.matcher(value).matches() || isOneRepeatedCharacter(value) || !hasValidCheckDigits(value)) {
            // The input is not echoed: it may identify a person.
            throw new IllegalArgumentException("Not a valid CNPJ");
        }
    }

    /**
     * Parses a CNPJ written as 14 characters ({@code 12ABC34501DE35}) or with the usual punctuation
     * ({@code 12.ABC.345/01DE-35}). Lowercase letters, spaces and any other form are rejected, so each
     * CNPJ has one spelling.
     *
     * @throws IllegalArgumentException if the text is not a valid CNPJ
     */
    public static Cnpj parse(String text) {
        if (FORMATTED.matcher(text).matches()) {
            return new Cnpj(text.replace(".", "").replace("/", "").replace("-", ""));
        }
        return new Cnpj(text);
    }

    /** The CNPJ with its usual punctuation, such as {@code 12.ABC.345/01DE-35}. */
    public String formatted() {
        return value.substring(0, 2) + "." + value.substring(2, 5) + "." + value.substring(5, 8) + "/"
                + value.substring(8, 12) + "-" + value.substring(12);
    }

    /** The masked CNPJ, such as {@code **.ABC.345/01DE-**}: the first 2 and the check digits are hidden. */
    @Override
    public String toString() {
        return "**." + value.substring(2, 5) + "." + value.substring(5, 8) + "/" + value.substring(8, 12) + "-**";
    }

    private static boolean isOneRepeatedCharacter(String value) {
        return value.chars().allMatch(c -> c == value.charAt(0));
    }

    private static boolean hasValidCheckDigits(String value) {
        int[] values = value.chars().map(c -> c - '0').toArray();
        return CheckDigits.mod11(values, 12, MAX_WEIGHT) == values[12]
                && CheckDigits.mod11(values, 13, MAX_WEIGHT) == values[13];
    }
}

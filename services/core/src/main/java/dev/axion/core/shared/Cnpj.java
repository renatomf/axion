package dev.axion.core.shared;

import java.util.regex.Pattern;

/**
 * A company's taxpayer number: 12 characters and 2 numeric check digits, alphanumeric from July
 * 2026 (IN RFB 2,229/2024). Masked by {@link #toString}: a sole trader's CNPJ identifies a person.
 *
 * @param value the 14 characters, without punctuation
 */
public record Cnpj(String value) {

    private static final Pattern PLAIN = Pattern.compile("[0-9A-Z]{12}[0-9]{2}");
    private static final Pattern FORMATTED =
            Pattern.compile("[0-9A-Z]{2}\\.[0-9A-Z]{3}\\.[0-9A-Z]{3}/[0-9A-Z]{4}-[0-9]{2}");
    private static final int MAX_WEIGHT = 9;

    public Cnpj {
        if (!PLAIN.matcher(value).matches() || !CheckDigits.isValid(value, MAX_WEIGHT)) {
            // The input is not echoed: it may identify a person.
            throw new IllegalArgumentException("Not a valid CNPJ");
        }
    }

    /**
     * Accepts {@code 12ABC34501DE35} or {@code 12.ABC.345/01DE-35}; nothing else, so each CNPJ has one
     * spelling.
     *
     * @throws IllegalArgumentException if the text is not a valid CNPJ
     */
    public static Cnpj parse(String text) {
        if (FORMATTED.matcher(text).matches()) {
            return new Cnpj(text.replace(".", "").replace("/", "").replace("-", ""));
        }
        return new Cnpj(text);
    }

    public String formatted() {
        return value.substring(0, 2) + "." + value.substring(2, 5) + "." + value.substring(5, 8) + "/"
                + value.substring(8, 12) + "-" + value.substring(12);
    }

    @Override
    public String toString() {
        return "**." + value.substring(2, 5) + "." + value.substring(5, 8) + "/" + value.substring(8, 12) + "-**";
    }
}

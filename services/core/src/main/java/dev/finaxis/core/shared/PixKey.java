package dev.finaxis.core.shared;

import java.util.regex.Pattern;

/**
 * A Pix key in the canonical form the DICT stores, so two keys are equal exactly when they reach the
 * same entry. Every type but the random key is personal data, so {@link #toString} shows only the
 * type.
 */
public record PixKey(Type type, String value) {

    public enum Type {
        CPF,
        CNPJ,
        EMAIL,
        PHONE,
        /** A key the DICT generates, called EVP in its specification. */
        RANDOM
    }

    private static final int MAX_LENGTH = 77;
    private static final Pattern EMAIL = Pattern.compile(
            "[a-z0-9.!#$&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");
    // +55, a two-digit area code without zeros, and a nine-digit mobile number, which starts with 9.
    private static final Pattern PHONE = Pattern.compile("\\+55[1-9]{2}9[0-9]{8}");
    private static final Pattern RANDOM =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public PixKey {
        // Length first, so an oversized input is never matched against a pattern.
        if (value.length() > MAX_LENGTH || !isCanonical(type, value)) {
            // The input is not echoed: it may be personal data.
            throw new IllegalArgumentException("Not a valid Pix key of type " + type);
        }
    }

    /**
     * Tells the type from the form alone, since the five forms never overlap. Clients normalize
     * punctuation, case and the {@code +55} prefix before sending.
     *
     * @throws IllegalArgumentException if the text is not a Pix key in canonical form
     */
    public static PixKey parse(String text) {
        Type type;
        if (text.startsWith("+")) {
            type = Type.PHONE;
        } else if (text.contains("@")) {
            type = Type.EMAIL;
        } else if (text.length() == 36) {
            type = Type.RANDOM;
        } else if (text.length() == 14) {
            type = Type.CNPJ;
        } else {
            type = Type.CPF;
        }
        return new PixKey(type, text);
    }

    @Override
    public String toString() {
        return "PixKey[" + type + "]";
    }

    private static boolean isCanonical(Type type, String value) {
        return switch (type) {
            case CPF -> isValid(() -> new Cpf(value));
            case CNPJ -> isValid(() -> new Cnpj(value));
            case EMAIL -> EMAIL.matcher(value).matches();
            case PHONE -> PHONE.matcher(value).matches();
            case RANDOM -> RANDOM.matcher(value).matches();
        };
    }

    private static boolean isValid(Runnable construction) {
        try {
            construction.run();
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

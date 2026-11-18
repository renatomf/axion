package dev.finaxis.core.shared;

import java.util.regex.Pattern;

/**
 * A Pix key: the alias, registered in the Central Bank's directory (DICT), that a payer uses to
 * reach an account instead of its bank details.
 *
 * <p>The value is in the canonical form the DICT stores, one per type, so two keys are equal exactly
 * when they reach the same entry:
 *
 * <ul>
 *   <li>{@link Type#CPF}: 11 digits, without punctuation, such as {@code 52998224725}
 *   <li>{@link Type#CNPJ}: 14 characters, without punctuation, such as {@code 12ABC34501DE35}
 *   <li>{@link Type#EMAIL}: lowercase, up to 77 characters, such as {@code maria@example.com}
 *   <li>{@link Type#PHONE}: a Brazilian mobile number in E.164, such as {@code +5511987654321}
 *   <li>{@link Type#RANDOM}: a lowercase UUID the DICT generated, such as {@code
 *       123e4567-e89b-42d3-a456-426614174000}
 * </ul>
 *
 * <p>Every type but the random key is personal data, so {@link #toString} shows only the type.
 *
 * @param type what the key is
 * @param value the key in its canonical form
 */
public record PixKey(Type type, String value) {

    /** The types of Pix key. */
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

    /** Rejects a value that is not the canonical form of a key of the given type. */
    public PixKey {
        // The length is checked first, so an oversized input is never matched against a pattern.
        if (value.length() > MAX_LENGTH || !isCanonical(type, value)) {
            // The input is not echoed: it may be personal data.
            throw new IllegalArgumentException("Not a valid Pix key of type " + type);
        }
    }

    /**
     * Parses a key in its canonical form, telling its type from the form alone: the forms of the five
     * types never overlap. A CPF or CNPJ with punctuation, an email with uppercase letters and a phone
     * number without {@code +55} are rejected; a client normalizes them before sending.
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

    /** The key with its value hidden, such as {@code PixKey[EMAIL]}. */
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

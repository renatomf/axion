package dev.finaxis.core.billing.domain;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * The identifier of a charge on the rails, the {@code txid} that the BR Code carries and the credit
 * notification returns (billing design, section 5.2). Issued as 25 random letters or digits, about
 * 148 bits; a notification may name any 1 to 25 of them, the format of the BR Code's subfield.
 */
public record TransactionId(String value) {

    /** The most that subfield 05 of the BR Code's additional data field holds. */
    public static final int LENGTH = 25;

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9]{1," + LENGTH + "}");

    public TransactionId {
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("A transaction ID is 1 to " + LENGTH + " letters or digits");
        }
    }

    /**
     * A new transaction ID; {@code random} is a {@code SecureRandom}, so charges cannot be found by
     * guessing it. Not derived from the charge's ID, which would reveal when it was created.
     */
    public static TransactionId generate(SecureRandom random) {
        var text = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            text.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return new TransactionId(text.toString());
    }
}

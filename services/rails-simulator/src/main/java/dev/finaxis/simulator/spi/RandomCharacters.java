package dev.finaxis.simulator.spi;

import java.security.SecureRandom;

/** The random tail of the SPI's identifiers: letters and digits from {@link SecureRandom}. */
final class RandomCharacters {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private RandomCharacters() {}

    static String draw(int length) {
        var characters = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            characters.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return characters.toString();
    }
}

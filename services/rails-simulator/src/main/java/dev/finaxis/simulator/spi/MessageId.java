package dev.finaxis.simulator.spi;

import java.util.regex.Pattern;

/**
 * The identity of one message: {@code M}, the sender's ISPB and 23 random letters or digits, in the
 * layout of the {@link EndToEndId} (rails simulator design, section 5.2). The copies of a message share it.
 *
 * @param value the 32 characters
 */
public record MessageId(String value) {

    private static final Pattern FORMAT = Pattern.compile("M[0-9]{8}[A-Za-z0-9]{23}");
    private static final int RANDOM_LENGTH = 23;

    public MessageId {
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a messageId: " + value);
        }
    }

    public static MessageId generate(Ispb sender) {
        return new MessageId("M" + sender.digits() + RandomCharacters.draw(RANDOM_LENGTH));
    }

    @Override
    public String toString() {
        return value;
    }
}

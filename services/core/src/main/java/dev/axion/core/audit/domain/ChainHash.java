package dev.axion.core.audit.domain;

import java.util.Arrays;
import java.util.HexFormat;

/** A SHA-256 hash in an audit chain: a record's own, or the one before it that it links to. */
public final class ChainHash {

    public static final int LENGTH = 32;

    /** What the first record of a chain links to, and the head of a chain with no record yet. */
    public static final ChainHash ZERO = new ChainHash(new byte[LENGTH]);

    private final byte[] bytes;

    private ChainHash(byte[] bytes) {
        this.bytes = bytes;
    }

    /**
     * A hash read from a record or a chain's head.
     *
     * @throws IllegalArgumentException unless the bytes are 32
     */
    public static ChainHash of(byte[] bytes) {
        if (bytes.length != LENGTH) {
            throw new IllegalArgumentException("A chain hash is " + LENGTH + " bytes, not " + bytes.length);
        }
        return new ChainHash(bytes.clone());
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ChainHash hash && Arrays.equals(bytes, hash.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    /** Lowercase hex, as the verification job logs a chain's head. */
    @Override
    public String toString() {
        return HexFormat.of().formatHex(bytes);
    }
}

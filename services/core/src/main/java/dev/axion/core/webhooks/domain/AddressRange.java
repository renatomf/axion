package dev.axion.core.webhooks.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** A block of IP addresses in CIDR notation, such as {@code 10.0.0.0/8} or {@code fc00::/7}. */
final class AddressRange {

    private final byte[] prefix;
    private final int bits;

    private AddressRange(byte[] prefix, int bits) {
        if (bits < 0 || bits > prefix.length * 8) {
            throw new IllegalArgumentException("A /" + bits + " prefix does not fit the address");
        }
        this.prefix = prefix;
        this.bits = bits;
    }

    /** Parses a range written by the core itself, so its address is always an IP literal. */
    static AddressRange parse(String cidr) {
        int slash = cidr.indexOf('/');
        String literal = cidr.substring(0, slash);
        try {
            // An IP literal is parsed in place; getByName looks up nothing.
            byte[] address = InetAddress.getByName(literal).getAddress();
            if (literal.contains(":") && address.length == 4) {
                // getByName returns ::ffff:a.b.c.d as a.b.c.d; the range keeps the IPv6 form.
                address = mapped(address);
            }
            return new AddressRange(address, Integer.parseInt(cidr.substring(slash + 1)));
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Not an IP literal: " + cidr, e);
        }
    }

    /** Whether the address, given as its 4 or 16 bytes, starts with this range's prefix. */
    boolean contains(byte[] address) {
        if (address.length != prefix.length) {
            return false;
        }
        int wholeBytes = bits / 8;
        if (!Arrays.equals(address, 0, wholeBytes, prefix, 0, wholeBytes)) {
            return false;
        }
        int remainingBits = bits % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits);
        return (address[wholeBytes] & mask) == (prefix[wholeBytes] & mask);
    }

    private static byte[] mapped(byte[] ipv4) {
        byte[] address = new byte[16];
        address[10] = (byte) 0xff;
        address[11] = (byte) 0xff;
        System.arraycopy(ipv4, 0, address, 12, 4);
        return address;
    }
}

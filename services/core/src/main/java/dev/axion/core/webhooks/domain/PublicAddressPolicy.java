package dev.axion.core.webhooks.domain;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

/**
 * Which addresses the core may send a webhook to (events and webhooks design, section 7.1): any
 * address outside the loopback, private, link-local, shared, documentation, multicast and reserved
 * ranges. An IPv4 address written in an IPv6 form is judged as the IPv4 address it carries, so
 * {@code ::ffff:127.0.0.1} is loopback. The same rule checks a URL at registration and every
 * address a delivery connects to.
 */
public final class PublicAddressPolicy {

    private static final List<AddressRange> REFUSED_IPV4 = ranges(
            "0.0.0.0/8",
            "127.0.0.0/8",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "100.64.0.0/10",
            "169.254.0.0/16",
            "192.0.0.0/24",
            "192.0.2.0/24",
            "198.18.0.0/15",
            "198.51.100.0/24",
            "203.0.113.0/24",
            "224.0.0.0/4",
            "240.0.0.0/4");

    private static final List<AddressRange> REFUSED_IPV6 =
            ranges("::/128", "::1/128", "fc00::/7", "fe80::/10", "2001:db8::/32", "ff00::/8");

    private static final AddressRange MAPPED = AddressRange.parse("::ffff:0:0/96");
    private static final AddressRange TRANSLATED = AddressRange.parse("64:ff9b::/96");
    private static final AddressRange SIX_TO_FOUR = AddressRange.parse("2002::/16");

    private PublicAddressPolicy() {}

    public static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 ? isPublicIpv4(bytes) : isPublicIpv6(bytes);
    }

    private static boolean isPublicIpv4(byte[] address) {
        return REFUSED_IPV4.stream().noneMatch(range -> range.contains(address));
    }

    private static boolean isPublicIpv6(byte[] address) {
        // The IPv4 address sits in the last 32 bits of the mapped and translated forms, and in
        // the 32 bits after the 2002 prefix of a 6to4 address.
        if (MAPPED.contains(address) || TRANSLATED.contains(address)) {
            return isPublicIpv4(Arrays.copyOfRange(address, 12, 16));
        }
        if (SIX_TO_FOUR.contains(address)) {
            return isPublicIpv4(Arrays.copyOfRange(address, 2, 6));
        }
        return REFUSED_IPV6.stream().noneMatch(range -> range.contains(address));
    }

    private static List<AddressRange> ranges(String... cidrs) {
        return Arrays.stream(cidrs).map(AddressRange::parse).toList();
    }
}

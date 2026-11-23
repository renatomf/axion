package dev.axion.core.webhooks.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * Properties of {@link PublicAddressPolicy} over generated addresses, against the table of section
 * 7.1 written again here as numbers, so the test does not share the policy's code: every address of
 * a refused range is refused in each of its forms, and every other address is accepted.
 */
class PublicAddressPolicyPropertiesTest {

    private record Range(BigInteger first, int bits, int length) {

        static Range of(String cidr) {
            int slash = cidr.indexOf('/');
            byte[] address = bytes(cidr.substring(0, slash));
            return new Range(
                    new BigInteger(1, address), Integer.parseInt(cidr.substring(slash + 1)), address.length * 8);
        }

        boolean contains(BigInteger address) {
            return address.shiftRight(length - bits).equals(first.shiftRight(length - bits));
        }

        BigInteger with(BigInteger hostBits) {
            BigInteger hostMask = BigInteger.ONE.shiftLeft(length - bits).subtract(BigInteger.ONE);
            return first.andNot(hostMask).or(hostBits.and(hostMask));
        }
    }

    private static final List<Range> REFUSED_IPV4 = List.of(
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
                    "240.0.0.0/4")
            .stream()
            .map(Range::of)
            .toList();

    private static final List<Range> REFUSED_IPV6 =
            List.of("::/128", "::1/128", "fc00::/7", "fe80::/10", "2001:db8::/32", "ff00::/8").stream()
                    .map(Range::of)
                    .toList();

    /** The IPv6 forms that carry an IPv4 address: mapped, translated and 6to4. */
    private static final List<Range> IPV4_CARRIERS = List.of("::ffff:0:0/96", "64:ff9b::/96", "2002::/16").stream()
            .map(Range::of)
            .toList();

    @Property(tries = 2000)
    void refusesEveryIpv4AddressOfARefusedRangeInEveryForm(
            @ForAll("refusedIpv4Ranges") Range range, @ForAll("ipv4") BigInteger hostBits) {
        BigInteger address = range.with(hostBits);

        assertThat(formsOf(address)).noneMatch(PublicAddressPolicy::isPublic);
    }

    @Property(tries = 2000)
    void refusesEveryIpv6AddressOfARefusedRange(
            @ForAll("refusedIpv6Ranges") Range range, @ForAll("ipv6") BigInteger hostBits) {
        assertThat(PublicAddressPolicy.isPublic(ipv6(range.with(hostBits)))).isFalse();
    }

    @Property(tries = 2000)
    void acceptsEveryOtherIpv4AddressInEveryForm(@ForAll("ipv4") BigInteger address) {
        if (REFUSED_IPV4.stream().anyMatch(range -> range.contains(address))) {
            return;
        }

        assertThat(formsOf(address)).allMatch(PublicAddressPolicy::isPublic);
    }

    @Property(tries = 2000)
    void acceptsEveryOtherIpv6Address(@ForAll("ipv6") BigInteger address) {
        if (REFUSED_IPV6.stream().anyMatch(range -> range.contains(address))
                || IPV4_CARRIERS.stream().anyMatch(range -> range.contains(address))) {
            return;
        }

        assertThat(PublicAddressPolicy.isPublic(ipv6(address))).isTrue();
    }

    @Provide
    Arbitrary<Range> refusedIpv4Ranges() {
        return Arbitraries.of(REFUSED_IPV4);
    }

    @Provide
    Arbitrary<Range> refusedIpv6Ranges() {
        return Arbitraries.of(REFUSED_IPV6);
    }

    @Provide
    Arbitrary<BigInteger> ipv4() {
        return Arbitraries.bigIntegers()
                .between(BigInteger.ZERO, BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE));
    }

    /** Any 128 bits, with a quarter of them in the global unicast block, where real addresses live. */
    @Provide
    Arbitrary<BigInteger> ipv6() {
        BigInteger max = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
        Arbitrary<BigInteger> any = Arbitraries.bigIntegers().between(BigInteger.ZERO, max);
        Arbitrary<BigInteger> globalUnicast = any.map(bits -> BigInteger.ONE
                .shiftLeft(125)
                .or(bits.and(BigInteger.ONE.shiftLeft(125).subtract(BigInteger.ONE))));
        return Arbitraries.frequencyOf(Tuple.of(3, any), Tuple.of(1, globalUnicast));
    }

    /** The address as IPv4 and in each IPv6 form that carries it. */
    private static List<InetAddress> formsOf(BigInteger ipv4) {
        return List.of(
                address(ipv4, 4),
                ipv6(BigInteger.valueOf(0xffff).shiftLeft(32).or(ipv4)),
                ipv6(new BigInteger("64ff9b", 16).shiftLeft(96).or(ipv4)),
                ipv6(BigInteger.valueOf(0x2002).shiftLeft(112).or(ipv4.shiftLeft(80))));
    }

    // Inet6Address.getByAddress keeps a mapped address in its IPv6 form, as a resolver may return it.
    private static InetAddress ipv6(BigInteger bits) {
        try {
            return Inet6Address.getByAddress(null, toBytes(bits, 16), -1);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InetAddress address(BigInteger bits, int length) {
        try {
            return InetAddress.getByAddress(toBytes(bits, length));
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] toBytes(BigInteger bits, int length) {
        byte[] signed = bits.toByteArray();
        byte[] bytes = new byte[length];
        int copied = Math.min(signed.length, length);
        System.arraycopy(signed, signed.length - copied, bytes, length - copied, copied);
        return bytes;
    }

    // getByName returns ::ffff:a.b.c.d as a.b.c.d; the range keeps the IPv6 form it was written in.
    private static byte[] bytes(String literal) {
        try {
            byte[] address = InetAddress.getByName(literal).getAddress();
            return literal.contains(":") && address.length == 4
                    ? toBytes(BigInteger.valueOf(0xffff).shiftLeft(32).or(new BigInteger(1, address)), 16)
                    : address;
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}

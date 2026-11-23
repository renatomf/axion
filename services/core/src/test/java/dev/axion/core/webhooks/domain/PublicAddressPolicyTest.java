package dev.axion.core.webhooks.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The addresses of section 7.1 the attacks aim at, and the edges of each refused range. */
class PublicAddressPolicyTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "169.254.169.254",
                "fd00:ec2::254",
                "127.0.0.1",
                "::1",
                "0.0.0.0",
                "::",
                "10.0.0.1",
                "172.16.0.0",
                "172.31.255.255",
                "192.168.1.1",
                "100.64.0.0",
                "100.127.255.255",
                "fe80::1",
                "ff02::1",
                "255.255.255.255",
                "2001:db8::1",
                "64:ff9b::a9fe:a9fe",
                "2002:7f00:1::",
                "2002:a9fe:a9fe::1"
            })
    void refusesAddressesInsideTheCore(String literal) throws UnknownHostException {
        assertThat(PublicAddressPolicy.isPublic(InetAddress.getByName(literal))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "8.8.8.8",
                "1.1.1.1",
                "9.255.255.255",
                "11.0.0.0",
                "172.15.255.255",
                "172.32.0.0",
                "100.63.255.255",
                "100.128.0.0",
                "169.253.255.255",
                "169.255.0.0",
                "223.255.255.255",
                "2606:4700:4700::1111",
                "64:ff9b::808:808",
                "2002:808:808::1"
            })
    void acceptsPublicAddresses(String literal) throws UnknownHostException {
        assertThat(PublicAddressPolicy.isPublic(InetAddress.getByName(literal))).isTrue();
    }

    /** {@code getByName} turns the mapped form into IPv4, so the test builds the IPv6 address itself. */
    @Test
    void refusesALoopbackAddressInTheMappedForm() throws UnknownHostException {
        byte[] mapped = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 127, 0, 0, 1};

        assertThat(PublicAddressPolicy.isPublic(Inet6Address.getByAddress(null, mapped, -1)))
                .isFalse();
    }
}

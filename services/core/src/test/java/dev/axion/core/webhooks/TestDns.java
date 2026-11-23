package dev.axion.core.webhooks;

import dev.axion.core.webhooks.domain.HostResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Resolves the tests' names without the network, so registering an endpoint does not depend on the
 * machine's DNS: every name is public, except those under {@value #PRIVATE}, which resolve to a
 * private address, and under {@value #UNRESOLVABLE}, which do not resolve.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDns {

    public static final String PRIVATE = "private.example.com";

    public static final String UNRESOLVABLE = "unresolvable.example.com";

    private static final byte[] PUBLIC_ADDRESS = {93, (byte) 184, (byte) 215, 14};

    private static final byte[] PRIVATE_ADDRESS = {10, 0, 0, 7};

    @Bean
    @Primary
    HostResolver testHostResolver() {
        return host -> {
            if (host.endsWith(UNRESOLVABLE)) {
                throw new UnknownHostException(host);
            }
            byte[] address = host.endsWith(PRIVATE) ? PRIVATE_ADDRESS : PUBLIC_ADDRESS;
            return List.of(InetAddress.getByAddress(host, address));
        };
    }
}

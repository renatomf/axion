package dev.axion.core.webhooks.http;

import dev.axion.core.webhooks.domain.HostResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.function.Predicate;
import org.apache.hc.client5.http.DnsResolver;

/**
 * The HTTP client's only resolver (events and webhooks design, section 7.2): it resolves the name,
 * drops every address the policy refuses, and fails when none is left. The client connects to an
 * address this resolver returned, so no second lookup can lead it elsewhere, which defeats DNS
 * rebinding: a name that pointed to a public address at registration and to a private one now.
 */
public final class CheckedDnsResolver implements DnsResolver {

    private final HostResolver resolver;
    private final Predicate<InetAddress> allowed;

    /** @param allowed the addresses that may receive webhooks, {@code PublicAddressPolicy::isPublic} outside the tests */
    public CheckedDnsResolver(HostResolver resolver, Predicate<InetAddress> allowed) {
        this.resolver = resolver;
        this.allowed = allowed;
    }

    /**
     * @throws RefusedAddressException if the name resolves only to refused addresses
     * @throws UnknownHostException if it does not resolve
     */
    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] checked = resolver.resolve(host).stream().filter(allowed).toArray(InetAddress[]::new);
        if (checked.length == 0) {
            throw new RefusedAddressException(host);
        }
        return checked;
    }

    // No reverse lookup: the certificate is verified against the name of the URL.
    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }
}

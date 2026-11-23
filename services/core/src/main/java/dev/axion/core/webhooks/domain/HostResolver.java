package dev.axion.core.webhooks.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** Resolves a DNS name to its addresses; the system's resolver outside the tests. */
@FunctionalInterface
public interface HostResolver {

    HostResolver SYSTEM = host -> List.of(InetAddress.getAllByName(host));

    /**
     * Every address the name resolves to, IPv4 and IPv6.
     *
     * @throws UnknownHostException if the name does not resolve
     */
    List<InetAddress> resolve(String host) throws UnknownHostException;
}

package dev.axion.core.webhooks.domain;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The ports of {@code localhost} where a developer receives webhooks on their own machine (events
 * and webhooks design, section 7.2). Only the {@code local} profile lists any; everywhere else the
 * core reaches no address of its own host. On these ports a URL may be {@code http}: TLS on the
 * loopback interface protects nothing.
 */
public record LocalReceivers(Set<Integer> ports) {

    public static final LocalReceivers NONE = new LocalReceivers(Set.of());

    private static final String HOST = "localhost";

    private static final int FIRST_UNPRIVILEGED_PORT = 1024;
    private static final int LAST_PORT = 65_535;

    public LocalReceivers {
        ports = Set.copyOf(ports);
        for (int port : ports) {
            if (port < FIRST_UNPRIVILEGED_PORT || port > LAST_PORT) {
                throw new IllegalArgumentException("A local receiver's port is between 1024 and 65535, not " + port);
            }
        }
    }

    /** Whether the URL is {@code http} or {@code https} to {@code localhost} on one of the ports. */
    public boolean accepts(URI url) {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        return (scheme.equals("http") || scheme.equals("https"))
                && receivesOn(url.getHost())
                && url.getRawUserInfo() == null
                && url.getRawFragment() == null
                && ports.contains(url.getPort());
    }

    /** Whether requests to the host go to this machine's loopback interface. */
    public boolean receivesOn(@Nullable String host) {
        return !ports.isEmpty() && HOST.equalsIgnoreCase(host);
    }
}

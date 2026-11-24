package dev.axion.core.webhooks.domain;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks a webhook URL when it is registered (events and webhooks design, section 7.1): an {@code
 * https} URL to a public DNS name, on port 443 or an unprivileged port, whose every address is
 * public. The rules that need no lookup run first, so a refused name is never sent to the resolver.
 * A developer's {@link LocalReceivers} are accepted before every rule.
 */
public final class WebhookUrlValidator {

    private static final int DEFAULT_PORT = 443;
    private static final int FIRST_UNPRIVILEGED_PORT = 1024;
    private static final int LAST_PORT = 65_535;
    private static final int MAX_NAME_LENGTH = 253;

    // Two or more letter-digit-hyphen labels, the last starting with a letter, as every top-level domain does.
    private static final Pattern DNS_NAME =
            Pattern.compile("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?");
    // A host whose last label is a number is IPv4 to resolvers: 93.184.215.14, or 2130706433 for
    // 127.0.0.1. java.net.URI already refuses a last label such as 0x7f or 1 after other labels.
    private static final Pattern IPV4_LAST_LABEL = Pattern.compile("(?:.*\\.)?[0-9]+");

    // Names that never leave the local network: RFC 6761 and 6762, ICANN's .internal and RFC 8375.
    private static final List<String> LOCAL_SUFFIXES = List.of(".localhost", ".local", ".internal", ".home.arpa");

    private final HostResolver resolver;
    private final LocalReceivers local;

    public WebhookUrlValidator(HostResolver resolver) {
        this(resolver, LocalReceivers.NONE);
    }

    public WebhookUrlValidator(HostResolver resolver, LocalReceivers local) {
        this.resolver = resolver;
        this.local = local;
    }

    public UrlValidation validate(String text) {
        URI url;
        try {
            url = new URI(text);
        } catch (URISyntaxException e) {
            return refused(UrlRefusal.MALFORMED);
        }
        if (local.accepts(url)) {
            return new UrlValidation.Accepted(url);
        }
        if (!"https".equalsIgnoreCase(url.getScheme())) {
            return refused(UrlRefusal.MUST_BE_HTTPS);
        }
        String rawHost = url.getHost();
        if (rawHost == null || url.getRawUserInfo() != null || url.getRawFragment() != null) {
            return refused(UrlRefusal.MALFORMED);
        }
        // A fully qualified name may end with a dot; the rules apply to the name without it.
        String host = rawHost.toLowerCase(Locale.ROOT);
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (name.startsWith("[") || IPV4_LAST_LABEL.matcher(name).matches()) {
            return refused(UrlRefusal.MUST_USE_DOMAIN_NAME);
        }
        if (isLocal(name)) {
            return refused(UrlRefusal.MUST_BE_PUBLIC);
        }
        if (name.length() > MAX_NAME_LENGTH || !DNS_NAME.matcher(name).matches()) {
            // A single label is completed with the resolver's search domains, so it names a local host.
            return refused(name.contains(".") ? UrlRefusal.MALFORMED : UrlRefusal.MUST_BE_PUBLIC);
        }
        int port = url.getPort();
        if (port != -1 && port != DEFAULT_PORT && (port < FIRST_UNPRIVILEGED_PORT || port > LAST_PORT)) {
            return refused(UrlRefusal.PORT_NOT_ALLOWED);
        }
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            return refused(UrlRefusal.MUST_RESOLVE);
        }
        if (addresses.isEmpty()) {
            return refused(UrlRefusal.MUST_RESOLVE);
        }
        if (!addresses.stream().allMatch(PublicAddressPolicy::isPublic)) {
            return refused(UrlRefusal.MUST_BE_PUBLIC);
        }
        return new UrlValidation.Accepted(url);
    }

    private static boolean isLocal(String name) {
        return name.equals("localhost") || LOCAL_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private static UrlValidation refused(UrlRefusal reason) {
        return new UrlValidation.Refused(reason);
    }
}

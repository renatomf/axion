package dev.axion.core.webhooks.domain;

/** Why a webhook URL was refused (events and webhooks design, section 7.1), in the order the rules are checked. */
public enum UrlRefusal {
    /** Not an absolute URL with a host, or one with user information or a fragment. */
    MALFORMED,
    /** The scheme is not {@code https}. */
    MUST_BE_HTTPS,
    /** The host is an IP address, in any notation, instead of a DNS name. */
    MUST_USE_DOMAIN_NAME,
    /** The port is neither 443 nor between 1024 and 65535. */
    PORT_NOT_ALLOWED,
    /** The name is not under a public domain, or resolves to an address that is not public. */
    MUST_BE_PUBLIC,
    /** The name does not resolve. */
    MUST_RESOLVE
}

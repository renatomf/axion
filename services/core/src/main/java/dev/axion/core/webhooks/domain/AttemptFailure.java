package dev.axion.core.webhooks.domain;

/**
 * Why an attempt got no answer it could judge (events and webhooks design, section 8.7). An attempt
 * that got one has its status code instead.
 */
public enum AttemptFailure {
    /** No connection within the connect timeout, or no whole response within the response timeout. */
    TIMEOUT,
    /** The receiver's address refused the connection. */
    CONNECTION_REFUSED,
    /** Every address the name resolved to is one the core may not send to (section 7.2). */
    ADDRESS_REFUSED,
    /** The name did not resolve. */
    UNRESOLVED,
    /** The TLS handshake failed, such as for a certificate that does not match the name. */
    TLS_ERROR,
    /** The receiver answered with a redirect, which is never followed. */
    REDIRECT,
    /** The connection failed in another way, such as closed before a response. */
    CONNECTION_FAILED
}

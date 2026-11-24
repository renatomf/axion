package dev.axion.core.webhooks.http;

import java.net.UnknownHostException;

/**
 * A name that resolved only to addresses the core may not send to. An {@link UnknownHostException},
 * so the HTTP client fails the connection as for a name that does not resolve, and never tries
 * another address.
 */
public final class RefusedAddressException extends UnknownHostException {

    private static final long serialVersionUID = 1L;

    // The addresses are not in the message: refusals do not reveal what the name resolved to.
    RefusedAddressException(String host) {
        super("No address of " + host + " may receive webhooks");
    }
}

package dev.axion.core.webhooks.domain;

import java.net.URI;

/** The outcome of {@link WebhookUrlValidator#validate}. */
public sealed interface UrlValidation {

    record Accepted(URI url) implements UrlValidation {}

    /** The reason names the rule, never the addresses the name resolved to. */
    record Refused(UrlRefusal reason) implements UrlValidation {}
}

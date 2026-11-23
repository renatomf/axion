package dev.axion.core.webhooks.web;

import dev.axion.core.shared.PublicId;
import dev.axion.core.webhooks.EndpointCursor;
import dev.axion.core.webhooks.WebhookEndpointId;
import org.jspecify.annotations.Nullable;

/** An endpoint's ID as a path or a query names it. */
final class EndpointIds {

    /** The contract's pattern for an endpoint's ID. */
    static final String PATTERN = "we_[0-7][0-9a-hjkmnp-tv-z]{25}";

    private EndpointIds() {}

    // A malformed ID answers 404, like another tenant's endpoint, not 400.
    static WebhookEndpointId inPath(String text) {
        try {
            return parse(text);
        } catch (IllegalArgumentException malformed) {
            throw WebhookProblems.notFound();
        }
    }

    /** The page after {@code starting_after}, already checked against {@link #PATTERN}, or the first. */
    static EndpointCursor cursor(@Nullable String startingAfter) {
        return startingAfter == null ? EndpointCursor.first() : EndpointCursor.after(parse(startingAfter));
    }

    private static WebhookEndpointId parse(String text) {
        return new WebhookEndpointId(
                PublicId.parse(WebhookEndpointId.PREFIX, text).uuid());
    }
}

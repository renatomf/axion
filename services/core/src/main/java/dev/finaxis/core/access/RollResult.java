package dev.finaxis.core.access;

/**
 * The outcome of {@link AccessApi#roll}. Expected outcomes are values, not exceptions (overview
 * design, section 19.1), and the interface is sealed, so a {@code switch} over it must handle each
 * one.
 */
public sealed interface RollResult {

    /**
     * The key was rolled.
     *
     * @param replacement the new key, with its secret, which is in the result and nowhere else
     * @param rolled the old key as it is now, working until its {@code expiresAt}
     */
    record Rolled(IssuedApiKey replacement, ApiKeyView rolled) implements RollResult {}

    /** The member's tenant has no key with this ID, so nothing was rolled. */
    record KeyNotFound() implements RollResult {}
}

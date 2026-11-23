/**
 * Who may call the core and what they may do: members' access tokens, permissions, step-up and
 * {@code /internal/me}, and issuing and authenticating API keys through {@link
 * dev.axion.core.access.AccessApi}; rate limiting as it arrives.
 *
 * <p>The module is split by technical role, and Spring Modulith hides these subpackages from other
 * modules:
 *
 * <ul>
 *   <li>{@code application}: the implementation of {@code AccessApi}, which owns the transactions;
 *   <li>{@code domain}: the access model, such as the format of API keys, in plain Java;
 *   <li>{@code persistence}: the module's SQL;
 *   <li>{@code security}: the HTTP security filter chains and how credentials are validated;
 *   <li>{@code web}: the module's endpoints and the bodies they return.
 * </ul>
 */
@NullMarked
package dev.axion.core.access;

import org.jspecify.annotations.NullMarked;

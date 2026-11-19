/**
 * Who may call the core and what they may do: the validation of members' access tokens and {@code
 * /internal/me} now; permissions, step-up, API keys and rate limiting as they arrive.
 *
 * <p>The module is split by technical role, and Spring Modulith hides these subpackages from other
 * modules:
 *
 * <ul>
 *   <li>{@code domain}: the access model, such as the format of API keys, in plain Java;
 *   <li>{@code security}: the HTTP security filter chains and how credentials are validated;
 *   <li>{@code web}: the module's endpoints and the bodies they return.
 * </ul>
 */
@NullMarked
package dev.finaxis.core.access;

import org.jspecify.annotations.NullMarked;

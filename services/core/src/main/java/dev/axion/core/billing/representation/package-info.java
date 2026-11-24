/**
 * Charges, payments and refunds as the public API writes them, following the schemas of {@code
 * openapi/v1.yaml}. The billing endpoints return them, and the public events carry them, so a charge
 * in a webhook is the charge of {@code GET /v1/charges/{id}}.
 */
@NamedInterface("representation")
@NullMarked
package dev.axion.core.billing.representation;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;

/**
 * The ledger: the double-entry record of every money movement on the platform (ledger design,
 * {@code docs/design-docs/2026-11-18-ledger.md}).
 *
 * <p>Other modules will use it only through {@code LedgerApi}, in this package; the {@code domain}
 * subpackage holds the model and its invariants, in plain Java, and is internal to the module.
 */
@ApplicationModule(displayName = "Ledger")
@NullMarked
package dev.finaxis.core.ledger;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;

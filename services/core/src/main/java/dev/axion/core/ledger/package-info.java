/**
 * The double-entry record of every money movement (ledger design). Other modules use only {@link
 * LedgerApi} and the types in this package.
 */
@ApplicationModule(displayName = "Ledger")
@NullMarked
package dev.axion.core.ledger;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;

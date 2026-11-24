package dev.axion.core.webhooks.persistence;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a worker holds until {@link #leasedUntil()} and sends: a delivery's event to its endpoint's
 * URL, signed with the endpoint's secrets. Either one of the delivery's scheduled attempts or a
 * replay the tenant asked for.
 */
public sealed interface Claim permits ClaimedDelivery, ClaimedReplay {

    UUID delivery();

    /** The tenant whose log records the request. */
    UUID tenant();

    UUID endpoint();

    UUID event();

    URI url();

    /** The event as stored, UTF-8 JSON written once by the catalog. */
    String body();

    /**
     * The ciphertexts of the endpoint's secrets that sign, the current one first and a rolled one in
     * its grace period after it.
     */
    List<byte[]> secrets();

    Instant leasedUntil();
}

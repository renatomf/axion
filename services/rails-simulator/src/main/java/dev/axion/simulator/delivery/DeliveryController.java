package dev.axion.simulator.delivery;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** The deliveries of {@code openapi/simulator.yaml}: send a refused or failed delivery again. */
@RestController
class DeliveryController {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryController.class);

    private final Deliveries deliveries;

    DeliveryController(Deliveries deliveries) {
        this.deliveries = deliveries;
    }

    @PostMapping("/deliveries/{id}/redeliver")
    ResponseEntity<RedeliveryView> redeliver(@PathVariable String id) {
        Redelivery redelivery = parse(id).map(deliveries::redeliver).orElseGet(Redelivery.NotFound::new);
        return switch (redelivery) {
            case Redelivery.Created created -> {
                LOG.atInfo()
                        .addKeyValue("delivery", id)
                        .addKeyValue("redelivery", created.id())
                        .addKeyValue("end_to_end_id", created.endToEndId())
                        .log("Delivery redelivered");
                yield ResponseEntity.created(URI.create("/pix/payments/" + created.endToEndId()))
                        .body(new RedeliveryView(created.id().toString(), created.endToEndId()));
            }
            case Redelivery.NotFound notFound -> throw problem(HttpStatus.NOT_FOUND, "No delivery has this id.");
            case Redelivery.NotRedeliverable refused ->
                throw problem(
                        HttpStatus.CONFLICT,
                        "Only a refused or failed delivery can be redelivered; this one is "
                                + refused.status().name().toLowerCase(Locale.ROOT) + ".");
        };
    }

    // An id that cannot exist names no delivery: a 404, like one that does not.
    private static Optional<UUID> parse(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    private static ErrorResponseException problem(HttpStatus status, String detail) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }

    record RedeliveryView(String id, String endToEndId) {}
}

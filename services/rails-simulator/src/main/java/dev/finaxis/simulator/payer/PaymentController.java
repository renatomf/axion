package dev.finaxis.simulator.payer;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.finaxis.simulator.chaos.AppliedFault;
import dev.finaxis.simulator.chaos.Durations;
import dev.finaxis.simulator.delivery.Deliveries;
import dev.finaxis.simulator.delivery.Delivery;
import dev.finaxis.simulator.spi.Debtor;
import dev.finaxis.simulator.spi.EndToEndId;
import dev.finaxis.simulator.spi.Iso20022;
import dev.finaxis.simulator.spi.Iso20022.Amount;
import dev.finaxis.simulator.spi.Payment;
import dev.finaxis.simulator.spi.PaymentOrder;
import dev.finaxis.simulator.spi.Spi;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The payer API of {@code openapi/simulator.yaml}: a simulated payer pays a BR Code, and the payment is read back. */
@RestController
class PaymentController {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentController.class);

    /** The limit of the official remittance field, and of a name in the SPI's messages. */
    private static final int MAX_TEXT = 140;

    private final Spi spi;
    private final Deliveries deliveries;

    PaymentController(Spi spi, Deliveries deliveries) {
        this.spi = spi;
        this.deliveries = deliveries;
    }

    @PostMapping("/pix/payments")
    ResponseEntity<PaymentCreated> pay(@RequestBody PaymentRequest request) {
        String text = request.brCode();
        if (text == null) {
            throw badRequest("brCode is required.");
        }
        Debtor debtor = debtor(request.payer());
        String message = request.message();
        if (message != null && (message.isEmpty() || message.length() > MAX_TEXT)) {
            throw badRequest("message must have 1 to " + MAX_TEXT + " characters.");
        }
        BrCode code = BrCode.read(text);
        Payment payment = spi.settle(new PaymentOrder(
                code.amountMinor(), code.transactionId(), code.pixKey(), code.text(), debtor, message));
        // The EndToEndId only: the code's content, the payer and the message stay out of the logs.
        LOG.info("Settled Pix payment {}", payment.endToEndId());

        URI location = URI.create("/pix/payments/" + payment.endToEndId());
        String delivery =
                lowerCase(deliveries.of(payment.endToEndId().value()).getFirst().status());
        return ResponseEntity.created(location)
                .body(new PaymentCreated(
                        payment.endToEndId().value(), Iso20022.dateTime(payment.settledAt()), delivery));
    }

    @GetMapping("/pix/payments/{endToEndId}")
    PaymentView payment(@PathVariable String endToEndId) {
        return parse(endToEndId)
                .flatMap(spi::find)
                .map(payment -> PaymentView.of(
                        payment, deliveries.of(payment.endToEndId().value())))
                .orElseThrow(() -> new ErrorResponseException(
                        HttpStatus.NOT_FOUND,
                        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No payment has this EndToEndId."),
                        null));
    }

    @ExceptionHandler(InvalidBrCodeException.class)
    ProblemDetail refused(InvalidBrCodeException refusal) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, refusal.getMessage());
    }

    private static Debtor debtor(@Nullable Payer payer) {
        if (payer == null) {
            return SyntheticPayers.any();
        }
        String name = payer.name();
        String taxId = payer.taxId();
        if (name == null || name.isBlank() || name.length() > MAX_TEXT) {
            throw badRequest("payer.name must have 1 to " + MAX_TEXT + " characters.");
        }
        if (taxId == null || !Cpf.isValid(taxId)) {
            throw badRequest("payer.taxId must be a valid CPF of 11 digits.");
        }
        return new Debtor(name, taxId);
    }

    private static String lowerCase(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static @Nullable String dateTime(@Nullable Instant instant) {
        return instant == null ? null : Iso20022.dateTime(instant);
    }

    private static ErrorResponseException badRequest(String detail) {
        return new ErrorResponseException(
                HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
    }

    // An identifier that cannot exist names no payment: a 404, like one that does not.
    private static Optional<EndToEndId> parse(String endToEndId) {
        try {
            return Optional.of(new EndToEndId(endToEndId));
        } catch (IllegalArgumentException notAnEndToEndId) {
            return Optional.empty();
        }
    }

    /** Every field is nullable so that a body without one is a 400 with a detail, not a null inside the code. */
    record PaymentRequest(
            @Nullable String brCode,
            @Nullable Payer payer,
            @Nullable String message) {}

    record Payer(@Nullable String name, @Nullable String taxId) {

        // A payer's name and CPF are personal data, kept out of any log line that prints the request.
        @Override
        public String toString() {
            return "Payer[redacted]";
        }
    }

    record PaymentCreated(String endToEndId, String settledAt, String delivery) {}

    record PaymentView(
            String endToEndId,
            String transactionId,
            Amount interbankSettlementAmount,
            Agent debtorAgent,
            String settledAt,
            List<DeliveryView> deliveries) {

        static PaymentView of(Payment payment, List<Delivery> deliveries) {
            return new PaymentView(
                    payment.endToEndId().value(),
                    payment.transactionId(),
                    Amount.ofMinor(payment.amountMinor()),
                    new Agent(payment.debtorInstitution().digits()),
                    Iso20022.dateTime(payment.settledAt()),
                    deliveries.stream().map(DeliveryView::of).toList());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DeliveryView(
            String id,
            String kind,
            String status,
            String createdAt,
            @Nullable String nextAttemptAt,
            List<AttemptView> attempts,
            List<FaultView> faults) {

        static DeliveryView of(Delivery delivery) {
            return new DeliveryView(
                    delivery.id().toString(),
                    delivery.kind(),
                    lowerCase(delivery.status()),
                    Iso20022.dateTime(delivery.createdAt()),
                    dateTime(delivery.nextAttemptAt()),
                    delivery.attempts().stream().map(AttemptView::of).toList(),
                    delivery.faults().stream().map(FaultView::of).toList());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FaultView(
            String rule,
            String fault,
            @Nullable Integer attempt,
            @Nullable String waited) {

        static FaultView of(AppliedFault applied) {
            Duration waited = applied.waited();
            return new FaultView(
                    applied.rule().toString(),
                    applied.fault().tag(),
                    applied.attempt(),
                    waited == null ? null : Durations.inSeconds(waited));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AttemptView(
            int number,
            String attemptedAt,
            @Nullable Integer responseStatus,
            @Nullable String error,
            String outcome) {

        static AttemptView of(Delivery.Attempt attempt) {
            return new AttemptView(
                    attempt.number(),
                    Iso20022.dateTime(attempt.attemptedAt()),
                    attempt.responseStatus(),
                    attempt.error(),
                    attempt.outcome());
        }
    }

    record Agent(String ispb) {}
}

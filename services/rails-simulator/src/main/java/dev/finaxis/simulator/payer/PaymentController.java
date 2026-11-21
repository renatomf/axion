package dev.finaxis.simulator.payer;

import dev.finaxis.simulator.spi.EndToEndId;
import dev.finaxis.simulator.spi.Payment;
import dev.finaxis.simulator.spi.Spi;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    private static final DateTimeFormatter MILLISECONDS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final Spi spi;

    PaymentController(Spi spi) {
        this.spi = spi;
    }

    @PostMapping("/pix/payments")
    ResponseEntity<PaymentCreated> pay(@RequestBody PaymentRequest request) {
        String text = request.brCode();
        if (text == null) {
            throw new ErrorResponseException(
                    HttpStatus.BAD_REQUEST,
                    ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "brCode is required."),
                    null);
        }
        BrCode code = BrCode.read(text);
        Payment payment = spi.settle(code.amountMinor(), code.transactionId(), code.text());
        // The EndToEndId only: the code's content and the payer stay out of the logs.
        LOG.info("Settled Pix payment {}", payment.endToEndId());

        URI location = URI.create("/pix/payments/" + payment.endToEndId());
        return ResponseEntity.created(location)
                .body(new PaymentCreated(payment.endToEndId().value(), timestamp(payment.settledAt())));
    }

    @GetMapping("/pix/payments/{endToEndId}")
    PaymentView payment(@PathVariable String endToEndId) {
        return parse(endToEndId)
                .flatMap(spi::find)
                .map(PaymentView::of)
                .orElseThrow(() -> new ErrorResponseException(
                        HttpStatus.NOT_FOUND,
                        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No payment has this EndToEndId."),
                        null));
    }

    @ExceptionHandler(InvalidBrCodeException.class)
    ProblemDetail refused(InvalidBrCodeException refusal) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, refusal.getMessage());
    }

    // An identifier that cannot exist names no payment: a 404, like one that does not.
    private static Optional<EndToEndId> parse(String endToEndId) {
        try {
            return Optional.of(new EndToEndId(endToEndId));
        } catch (IllegalArgumentException notAnEndToEndId) {
            return Optional.empty();
        }
    }

    private static String timestamp(Instant instant) {
        return MILLISECONDS.format(instant);
    }

    /** {@code brCode} is nullable so that a body without it is a 400 with a detail, not a null inside the code. */
    record PaymentRequest(@Nullable String brCode) {}

    record PaymentCreated(String endToEndId, String settledAt) {}

    record PaymentView(
            String endToEndId,
            String transactionId,
            Amount interbankSettlementAmount,
            Agent debtorAgent,
            String settledAt) {

        static PaymentView of(Payment payment) {
            return new PaymentView(
                    payment.endToEndId().value(),
                    payment.transactionId(),
                    new Amount(BigDecimal.valueOf(payment.amountMinor(), 2).toPlainString(), "BRL"),
                    new Agent(payment.debtorInstitution().digits()),
                    timestamp(payment.settledAt()));
        }
    }

    record Amount(String amount, String currency) {}

    record Agent(String ispb) {}
}

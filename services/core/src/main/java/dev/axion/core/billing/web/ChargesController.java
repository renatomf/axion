package dev.axion.core.billing.web;

import dev.axion.core.access.RequiresPermission;
import dev.axion.core.billing.BillingApi;
import dev.axion.core.billing.ChargeCursor;
import dev.axion.core.billing.ChargeFilter;
import dev.axion.core.billing.ChargeId;
import dev.axion.core.billing.ChargeStatus;
import dev.axion.core.billing.CreateChargeResult;
import dev.axion.core.billing.RefundRefusal;
import dev.axion.core.billing.RefundResult;
import dev.axion.core.billing.domain.Charge;
import dev.axion.core.observability.CorrelationIdFilter;
import dev.axion.core.risk.RiskRule;
import dev.axion.core.shared.PublicId;
import dev.axion.core.tenancy.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ChargesController {

    private static final String PATH = "/v1/charges";

    // The contract's patterns for a charge's ID and status.
    private static final String CHARGE_ID = "ch_[0-7][0-9a-hjkmnp-tv-z]{25}";
    private static final String STATUS = "active|expired|paid|partially_refunded|refunded";

    private final BillingApi billing;

    ChargesController(BillingApi billing) {
        this.billing = billing;
    }

    // The tenant is the API key's, already bound; the idempotency step runs this inside the key's
    // transaction, which createCharge joins.
    @RequiresPermission(Permission.CHARGES_WRITE)
    @PostMapping(PATH)
    ResponseEntity<ChargeResponse> create(@Valid @RequestBody CreateChargeRequest request) {
        return switch (billing.createCharge(request.toChargeRequest())) {
            case CreateChargeResult.Created created -> {
                ChargeResponse charge = ChargeResponse.of(created.charge());
                yield ResponseEntity.created(URI.create(PATH + "/" + charge.id()))
                        .body(charge);
            }
            case CreateChargeResult.LimitExceeded denied -> throw limitExceeded(denied.rule());
        };
    }

    // The parameters are checked against the contract's rules before the call, so a malformed
    // starting_after or status is a 422 naming it, never an exception here.
    @RequiresPermission(Permission.CHARGES_READ)
    @GetMapping(PATH)
    ChargeListResponse list(
            @RequestParam(name = "limit", defaultValue = "10") @Min(1) @Max(BillingApi.MAX_PAGE_SIZE) int limit,
            @RequestParam(name = "starting_after", required = false) @Pattern(regexp = CHARGE_ID) @Nullable
                    String startingAfter,
            @RequestParam(name = "status", required = false) @Pattern(regexp = STATUS) @Nullable String status,
            @RequestParam(name = "created[gte]", required = false) @Nullable Instant createdFrom,
            @RequestParam(name = "created[lt]", required = false) @Nullable Instant createdBefore) {
        var filter = new ChargeFilter(
                Optional.ofNullable(status).map(name -> ChargeStatus.valueOf(name.toUpperCase(Locale.ROOT))),
                Optional.ofNullable(createdFrom),
                Optional.ofNullable(createdBefore));
        var cursor = startingAfter == null
                ? ChargeCursor.first()
                : ChargeCursor.after(new ChargeId(
                        PublicId.parse(ChargeId.PREFIX, startingAfter).uuid()));
        return ChargeListResponse.of(billing.charges(filter, cursor, limit));
    }

    @RequiresPermission(Permission.CHARGES_READ)
    @GetMapping(PATH + "/{id}")
    ChargeDetailResponse charge(@PathVariable("id") String id) {
        return billing.charge(chargeIn(id)).map(ChargeDetailResponse::of).orElseThrow(ChargesController::notFound);
    }

    // Like a charge's creation, the refund joins the key's transaction, and its correlation ID is
    // recorded with it, so a disputed refund leads to the request's logs.
    @RequiresPermission(Permission.REFUNDS_WRITE)
    @PostMapping(PATH + "/{id}/refunds")
    ResponseEntity<RefundResponse> refund(
            @PathVariable("id") String id, @Valid @RequestBody CreateRefundRequest request) {
        String correlationId = Objects.requireNonNull(
                MDC.get(CorrelationIdFilter.MDC_KEY), "Every request has a correlation ID before it reaches MVC");
        return switch (billing.refund(chargeIn(id), request.toRefundRequest(correlationId))) {
            case RefundResult.Refunded refunded ->
                ResponseEntity.status(HttpStatus.CREATED).body(RefundResponse.of(refunded.refund()));
            case RefundResult.Refused refused -> throw refundRefused(refused.reason());
        };
    }

    // A malformed ID answers 404, like another tenant's charge, not 400.
    private static ChargeId chargeIn(String text) {
        try {
            return new ChargeId(PublicId.parse(ChargeId.PREFIX, text).uuid());
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    // Thrown, not returned, so the idempotency step stores the refusal with the key and a retry
    // gets it again (billing design, section 4.3).
    private static ErrorResponseException limitExceeded(RiskRule rule) {
        String detail = switch (rule) {
            case AMOUNT -> "The amount exceeds the limit of one charge.";
            case DAILY_AMOUNT -> "The charge would exceed the amount of charges allowed today.";
            case HOURLY_COUNT -> "The charge would exceed the number of charges allowed this hour.";
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, detail);
        problem.setProperty("code", "limit_exceeded");
        problem.setProperty("rule", rule.name().toLowerCase(Locale.ROOT));
        return new ErrorResponseException(HttpStatus.UNPROCESSABLE_CONTENT, problem, null);
    }

    // Thrown for the same reason as limitExceeded. The codes are the contract's, so they are spelled
    // here rather than derived from the enum's names.
    private static ErrorResponseException refundRefused(RefundRefusal reason) {
        return switch (reason) {
            case CHARGE_NOT_FOUND -> notFound();
            case UNKNOWN_PAYMENT -> unknownPayment();
            case CHARGE_NOT_PAID -> unprocessable("charge_not_paid", "The charge has received no payment.");
            case PAYMENT_REQUIRED ->
                unprocessable("payment_required", "The charge has several payments; name the one to refund.");
            case AMOUNT_EXCEEDS_REFUNDABLE ->
                unprocessable("amount_exceeds_refundable", "The amount is more than remains of the payment.");
            case REFUND_WINDOW_CLOSED ->
                unprocessable(
                        "refund_window_closed",
                        "The payment was accepted more than " + Charge.REFUND_WINDOW.toDays() + " days ago.");
            case REFUND_LIMIT_REACHED ->
                unprocessable("refund_limit_reached", "The charge already has " + Charge.MAX_REFUNDS + " refunds.");
            case INSUFFICIENT_BALANCE ->
                unprocessable("insufficient_balance", "The available balance is less than the amount.");
        };
    }

    // A well-formed payment ID that is not the charge's is a field of the request that is wrong.
    private static ErrorResponseException unknownPayment() {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "The request has invalid fields.");
        problem.setProperty("code", "validation_failed");
        problem.setProperty("errors", List.of(Map.of("field", "payment", "detail", "is not a payment of the charge")));
        return new ErrorResponseException(HttpStatus.UNPROCESSABLE_CONTENT, problem, null);
    }

    private static ErrorResponseException unprocessable(String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, detail);
        problem.setProperty("code", code);
        return new ErrorResponseException(HttpStatus.UNPROCESSABLE_CONTENT, problem, null);
    }

    private static ErrorResponseException notFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The resource was not found.");
        problem.setProperty("code", "not_found");
        return new ErrorResponseException(HttpStatus.NOT_FOUND, problem, null);
    }
}

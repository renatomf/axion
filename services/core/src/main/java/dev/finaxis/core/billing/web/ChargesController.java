package dev.finaxis.core.billing.web;

import dev.finaxis.core.access.RequiresPermission;
import dev.finaxis.core.billing.BillingApi;
import dev.finaxis.core.billing.ChargeCursor;
import dev.finaxis.core.billing.ChargeFilter;
import dev.finaxis.core.billing.ChargeId;
import dev.finaxis.core.billing.ChargeStatus;
import dev.finaxis.core.billing.CreateChargeResult;
import dev.finaxis.core.shared.PublicId;
import dev.finaxis.core.tenancy.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
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
    ChargeResponse charge(@PathVariable("id") String id) {
        return billing.charge(chargeIn(id)).map(ChargeResponse::of).orElseThrow(ChargesController::notFound);
    }

    // A malformed ID answers 404, like another tenant's charge, not 400.
    private static ChargeId chargeIn(String text) {
        try {
            return new ChargeId(PublicId.parse(ChargeId.PREFIX, text).uuid());
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static ErrorResponseException notFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The resource was not found.");
        problem.setProperty("code", "not_found");
        return new ErrorResponseException(HttpStatus.NOT_FOUND, problem, null);
    }
}

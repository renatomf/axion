package dev.finaxis.core.billing.web;

import dev.finaxis.core.access.RequiresPermission;
import dev.finaxis.core.billing.BillingApi;
import dev.finaxis.core.billing.ChargeId;
import dev.finaxis.core.billing.CreateChargeResult;
import dev.finaxis.core.shared.PublicId;
import dev.finaxis.core.tenancy.Permission;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ChargesController {

    private static final String PATH = "/v1/charges";

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

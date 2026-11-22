package dev.finaxis.core.billing.web;

import dev.finaxis.core.access.RequiresPermission;
import dev.finaxis.core.billing.BillingApi;
import dev.finaxis.core.billing.CreateChargeResult;
import dev.finaxis.core.tenancy.Permission;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
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
}

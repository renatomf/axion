package dev.axion.core.billing.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.axion.core.billing.ChargePage;
import dev.axion.core.billing.representation.ChargeResponse;
import java.util.List;

record ChargeListResponse(
        String object,
        List<ChargeResponse> data,
        @JsonProperty("has_more") boolean hasMore) {

    static ChargeListResponse of(ChargePage page) {
        return new ChargeListResponse(
                "list", page.charges().stream().map(ChargeResponse::of).toList(), page.hasMore());
    }
}

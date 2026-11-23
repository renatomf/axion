package dev.finaxis.core.billing.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.finaxis.core.billing.ChargePage;
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

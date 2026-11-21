package dev.finaxis.simulator.spi;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.finaxis.simulator.spi.Iso20022.Amount;
import org.jspecify.annotations.Nullable;

/** The credit notification of {@code openapi/rails.yaml}, the counterpart of an ISO 20022 {@code pacs.008}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
record CreditNotification(
        String messageId,
        String messageType,
        String creationDateTime,
        String endToEndId,
        String transactionId,
        String localInstrument,
        Amount interbankSettlementAmount,
        String acceptanceDateTime,
        Debtor debtor,
        Account debtorAccount,
        Agent debtorAgent,
        Agent creditorAgent,
        @Nullable Proxy creditorAccount,
        @Nullable String remittanceInformation) {

    /** A credit transfer settled by the SPI. */
    static final String PACS_008 = "pacs.008";

    /** A payment of a dynamic QR code, every payment of phase 1. */
    static final String DYNAMIC_QR_CODE = "QRDN";

    /** A checking account. */
    static final String CHECKING = "CACC";

    record Account(String branch, String number, String type) {}

    record Agent(String ispb) {}

    record Proxy(String proxy) {}
}

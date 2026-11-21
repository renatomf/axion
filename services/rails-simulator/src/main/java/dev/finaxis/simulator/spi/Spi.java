package dev.finaxis.simulator.spi;

import dev.finaxis.simulator.delivery.Deliveries;
import dev.finaxis.simulator.spi.CreditNotification.Account;
import dev.finaxis.simulator.spi.CreditNotification.Agent;
import dev.finaxis.simulator.spi.CreditNotification.Proxy;
import dev.finaxis.simulator.spi.Iso20022.Amount;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Settles Pix payments as the payer's institution and the SPI would together: the institution debits
 * the payer and identifies the payment, and the SPI moves the money to Finaxis and notifies it (rails
 * simulator design, section 4.1). A settled payment always happened; nothing here can refuse or undo it.
 */
@Service
@EnableConfigurationProperties(ParticipantsProperties.class)
public class Spi {

    /** A collision of the 11 random characters within one minute and institution is rare; three in a row is a bug. */
    private static final int MAX_DRAWS = 3;

    private static final String BRANCH = "0001";
    private static final int ACCOUNT_NUMBERS = 1_000_000;

    private final PaymentRepository payments;
    private final Deliveries deliveries;
    private final JsonMapper json;
    private final Ispb receiver;
    private final List<Ispb> payerInstitutions;
    private final Clock clock;
    private final RandomGenerator random = RandomGenerator.getDefault();

    Spi(
            PaymentRepository payments,
            Deliveries deliveries,
            JsonMapper json,
            ParticipantsProperties participants,
            Clock clock) {
        this.payments = payments;
        this.deliveries = deliveries;
        this.json = json;
        this.receiver = participants.receiver();
        this.payerInstitutions = participants.payers();
        this.clock = clock;
    }

    /**
     * Settles a payment, debited by one of the payers' institutions chosen at random, and records the
     * credit notification to the core in the same transaction.
     */
    @Transactional
    public Payment settle(PaymentOrder order) {
        Ispb institution = payerInstitutions.get(random.nextInt(payerInstitutions.size()));
        Payment payment = record(order, institution);
        var notification = notification(payment, order);
        deliveries.schedule(
                payment.endToEndId().value(), notification.messageId(), json.writeValueAsBytes(notification));
        return payment;
    }

    public Optional<Payment> find(EndToEndId endToEndId) {
        return payments.find(endToEndId);
    }

    private Payment record(PaymentOrder order, Ispb institution) {
        var settledAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        for (int draw = 0; draw < MAX_DRAWS; draw++) {
            var payment = new Payment(
                    EndToEndId.generate(institution, settledAt),
                    order.transactionId(),
                    order.amountMinor(),
                    institution,
                    order.brCode(),
                    settledAt);
            if (payments.insertIfAbsent(payment)) {
                return payment;
            }
        }
        throw new IllegalStateException("No free EndToEndId after " + MAX_DRAWS + " draws");
    }

    private CreditNotification notification(Payment payment, PaymentOrder order) {
        String pixKey = order.pixKey();
        return new CreditNotification(
                MessageId.generate(payment.debtorInstitution()).value(),
                CreditNotification.PACS_008,
                Iso20022.dateTime(clock.instant()),
                payment.endToEndId().value(),
                payment.transactionId(),
                CreditNotification.DYNAMIC_QR_CODE,
                Amount.ofMinor(payment.amountMinor()),
                Iso20022.dateTime(payment.settledAt()),
                order.debtor(),
                new Account(BRANCH, String.valueOf(random.nextInt(ACCOUNT_NUMBERS)), CreditNotification.CHECKING),
                new Agent(payment.debtorInstitution().digits()),
                new Agent(receiver.digits()),
                pixKey == null ? null : new Proxy(pixKey),
                order.remittanceInformation());
    }
}

package dev.finaxis.simulator.spi;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Settles Pix payments as the payer's institution and the SPI would together: the institution debits
 * the payer and identifies the payment, and the SPI moves the money to Finaxis (rails simulator design,
 * section 4.1). A settled payment always happened; nothing here can refuse or undo it.
 */
@Service
@EnableConfigurationProperties(ParticipantsProperties.class)
public class Spi {

    /** A collision of the 11 random characters within one minute and institution is rare; three in a row is a bug. */
    private static final int MAX_DRAWS = 3;

    private final PaymentRepository payments;
    private final List<Ispb> payerInstitutions;
    private final Clock clock;
    private final RandomGenerator random = RandomGenerator.getDefault();

    Spi(PaymentRepository payments, ParticipantsProperties participants, Clock clock) {
        this.payments = payments;
        this.payerInstitutions = participants.payers();
        this.clock = clock;
    }

    /**
     * Settles a payment of a BR Code, debited by one of the payers' institutions chosen at random.
     *
     * @param amountMinor the amount in centavos, positive
     * @param transactionId the code's transaction ID
     * @param brCode the code as the payer read it
     */
    public Payment settle(long amountMinor, String transactionId, String brCode) {
        Ispb institution = payerInstitutions.get(random.nextInt(payerInstitutions.size()));
        var settledAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        for (int draw = 0; draw < MAX_DRAWS; draw++) {
            var payment = new Payment(
                    EndToEndId.generate(institution, settledAt),
                    transactionId,
                    amountMinor,
                    institution,
                    brCode,
                    settledAt);
            if (payments.insertIfAbsent(payment)) {
                return payment;
            }
        }
        throw new IllegalStateException("No free EndToEndId after " + MAX_DRAWS + " draws");
    }

    public Optional<Payment> find(EndToEndId endToEndId) {
        return payments.find(endToEndId);
    }
}

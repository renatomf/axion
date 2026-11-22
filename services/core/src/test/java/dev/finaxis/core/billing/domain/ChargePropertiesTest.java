package dev.finaxis.core.billing.domain;

import static dev.finaxis.core.billing.BillingFixtures.CREATED_AT;
import static dev.finaxis.core.billing.BillingFixtures.EXPIRES_AT;
import static dev.finaxis.core.billing.BillingFixtures.activeCharge;
import static dev.finaxis.core.billing.BillingFixtures.brl;
import static dev.finaxis.core.billing.BillingFixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.billing.ChargeStatus;
import dev.finaxis.core.billing.PaymentId;
import dev.finaxis.core.billing.RefundId;
import dev.finaxis.core.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Properties of every charge (billing design, section 13), checked by jqwik over generated
 * sequences of payments, refunds and expiry, in any order and at any time.
 */
class ChargePropertiesTest {

    private static final long LIFETIME_MILLIS =
            Duration.between(CREATED_AT, EXPIRES_AT).toMillis();

    @Property(tries = 1000)
    void anySequenceKeepsTheChargeConsistent(@ForAll("operations") List<Operation> operations) {
        Charge charge = activeCharge();
        Instant now = CREATED_AT;
        for (Operation operation : operations) {
            now = now.plusMillis(operation.advanceMillis());
            Charge before = charge;
            Charge after = operation.applyTo(charge, now);

            assertConsistent(after);
            assertThat(after.payments().subList(0, before.payments().size())).isEqualTo(before.payments());
            before.settlingPayment()
                    .ifPresent(settling -> assertThat(after.settlingPayment()).contains(settling));
            charge = after;
        }
    }

    private static void assertConsistent(Charge charge) {
        assertThat(charge.payments().stream().filter(Payment::isSettled)).hasSizeLessThanOrEqualTo(1);
        for (Payment payment : charge.payments()) {
            assertThat(charge.refundedFrom(payment.id()).minorUnits())
                    .isBetween(0L, payment.amount().minorUnits());
        }
        assertThat(charge.amountRefunded())
                .isEqualTo(charge.settlingPayment()
                        .map(p -> charge.refundedFrom(p.id()))
                        .orElse(brl(0)));
        assertThat(charge.status().isPaid()).isEqualTo(charge.settlingPayment().isPresent());
        if (charge.status() == ChargeStatus.REFUNDED) {
            assertThat(charge.amountRefunded()).isEqualTo(charge.amount());
        }
    }

    @Provide
    Arbitrary<List<Operation>> operations() {
        Arbitrary<Long> advance = Arbitraries.longs().between(0, LIFETIME_MILLIS / 4);
        Arbitrary<Operation> receive = Combinators.combine(
                        advance,
                        Arbitraries.of(10000L, 10000L, 9999L, 1L),
                        Arbitraries.longs().between(0, 2 * LIFETIME_MILLIS))
                .as(Receive::new);
        Arbitrary<Operation> refund = Combinators.combine(
                        advance,
                        Arbitraries.integers().between(-1, 3),
                        Arbitraries.longs().between(0, 12000))
                .as(RefundOne::new);
        Arbitrary<Operation> expire = advance.map(Expire::new);
        return Arbitraries.oneOf(receive, refund, expire).list().ofMaxSize(20);
    }

    /** One step of a charge's life, after {@code advanceMillis} of the core's clock. */
    private sealed interface Operation {
        long advanceMillis();

        Charge applyTo(Charge charge, Instant now);
    }

    /** A payment of {@code minorUnits}, accepted {@code acceptedAfterMillis} after the charge's creation. */
    private record Receive(long advanceMillis, long minorUnits, long acceptedAfterMillis) implements Operation {
        @Override
        public Charge applyTo(Charge charge, Instant now) {
            return charge.receive(payment(minorUnits, CREATED_AT.plusMillis(acceptedAfterMillis)), now)
                    .charge();
        }
    }

    /** A refund of the payment at {@code paymentIndex}, or of none named when negative; all that remains when 0. */
    private record RefundOne(long advanceMillis, int paymentIndex, long minorUnits) implements Operation {
        @Override
        public Charge applyTo(Charge charge, Instant now) {
            Optional<PaymentId> payment = paymentIndex < 0
                    ? Optional.empty()
                    : Optional.of(
                            paymentIndex < charge.payments().size()
                                    ? charge.payments().get(paymentIndex).id()
                                    : new PaymentId(UUID.randomUUID()));
            Optional<Money> amount = minorUnits == 0 ? Optional.empty() : Optional.of(brl(minorUnits));
            return switch (charge.refund(new RefundId(UUID.randomUUID()), payment, amount, now)) {
                case RefundDecision.Refunded refunded -> refunded.charge();
                case RefundDecision.Refused refused -> charge;
            };
        }
    }

    /** The expiry job's pass, which expires the charge only when it is active and due. */
    private record Expire(long advanceMillis) implements Operation {
        @Override
        public Charge applyTo(Charge charge, Instant now) {
            boolean due = charge.status() == ChargeStatus.ACTIVE && !now.isBefore(charge.expiresAt());
            return due ? charge.expire(now) : charge;
        }
    }
}

package dev.finaxis.core.rails.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.MutableClock;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.rails.CreditResult;
import dev.finaxis.core.rails.PixCredit;
import dev.finaxis.core.rails.PixCreditHandler;
import dev.finaxis.core.shared.Money;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The inbox's processor against a real PostgreSQL (billing design, section 7.3), handing credits to
 * a handler each test scripts. The clock starts long before any credit another test class records,
 * so only this class's credits are due.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, CreditProcessorTest.Configuration.class})
class CreditProcessorTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-01-05T12:00:00Z"));
        }

        @Bean
        @Primary
        ScriptedHandler scriptedHandler() {
            return new ScriptedHandler();
        }
    }

    /** Answers {@code RECORDED}, or what a test scripted for the credit, and remembers what it was given. */
    static final class ScriptedHandler implements PixCreditHandler {

        private final Map<String, Function<PixCredit, CreditResult>> scripts = new ConcurrentHashMap<>();
        private final List<PixCredit> handled = Collections.synchronizedList(new ArrayList<>());

        void script(PixCredit credit, Function<PixCredit, CreditResult> answer) {
            scripts.put(credit.endToEndId(), answer);
        }

        long timesHandled(PixCredit credit) {
            synchronized (handled) {
                return handled.stream().filter(credit::equals).count();
            }
        }

        @Override
        public CreditResult handle(PixCredit credit) {
            handled.add(credit);
            return scripts.getOrDefault(credit.endToEndId(), _ -> CreditResult.RECORDED)
                    .apply(credit);
        }
    }

    private final CreditProcessor processor;
    private final CreditInbox inbox;
    private final ScriptedHandler handler;
    private final MutableClock clock;
    private final JdbcClient owner;
    private final MeterRegistry meters;

    @Autowired
    CreditProcessorTest(
            CreditProcessor processor,
            CreditInbox inbox,
            ScriptedHandler handler,
            MutableClock clock,
            DatabaseOwner owner,
            MeterRegistry meters) {
        this.processor = processor;
        this.inbox = inbox;
        this.handler = handler;
        this.clock = clock;
        this.owner = owner.jdbc();
        this.meters = meters;
    }

    @Test
    void handsTheCreditOverAsTheInboxKeptIt() {
        PixCredit credit = record();

        processor.processDue();

        assertThat(handler.timesHandled(credit)).isOne();
    }

    @Test
    void marksARecordedCreditProcessed() {
        PixCredit credit = record();

        processor.processDue();

        assertThat(row(credit))
                .containsEntry("status", "processed")
                .containsEntry("attempts", 1)
                .containsEntry("next_attempt_at", null)
                .containsEntry("processed_at", Timestamp.from(clock.instant()));
    }

    @Test
    void marksACreditRecordedAlreadyProcessed() {
        PixCredit credit = record();
        handler.script(credit, _ -> CreditResult.ALREADY_RECORDED);

        processor.processDue();

        assertThat(row(credit)).containsEntry("status", "processed");
    }

    @Test
    void marksACreditOfAnUnknownTransactionUnmatchedAndCountsIt() {
        PixCredit credit = record();
        handler.script(credit, _ -> CreditResult.UNKNOWN_TRANSACTION);
        double before = unmatched();

        processor.processDue();

        assertThat(row(credit))
                .containsEntry("status", "unmatched")
                .containsEntry("next_attempt_at", null)
                .containsEntry("processed_at", Timestamp.from(clock.instant()));
        assertThat(unmatched()).isEqualTo(before + 1);
    }

    @Test
    void leasesAClaimedCreditWhileItIsHandled() {
        PixCredit credit = record();
        Instant claimedAt = clock.instant();
        List<@Nullable Object> leasedUntil = new ArrayList<>();
        handler.script(credit, handled -> {
            leasedUntil.add(row(handled).get("next_attempt_at"));
            return CreditResult.RECORDED;
        });

        processor.processDue();

        assertThat(leasedUntil).containsExactly(Timestamp.from(claimedAt.plus(Duration.ofSeconds(30))));
    }

    @Test
    void retriesAFailedCreditWithinASecondOfTheFirstFailure() {
        PixCredit credit = record();
        handler.script(credit, _ -> {
            throw new IllegalStateException("The database went away");
        });
        Instant failedAt = clock.instant();

        processor.processDue();

        Map<String, @Nullable Object> row = row(credit);
        assertThat(row).containsEntry("status", "received").containsEntry("attempts", 1);
        assertThat(((Timestamp) Objects.requireNonNull(row.get("next_attempt_at"))).toInstant())
                .isBetween(failedAt, failedAt.plusSeconds(1));
    }

    @Test
    void leavesACreditAloneUntilItIsDue() {
        PixCredit credit = record();
        owner.sql("UPDATE rails.pix_credits SET next_attempt_at = ? WHERE end_to_end_id = ?")
                .params(Timestamp.from(clock.instant().plusSeconds(60)), credit.endToEndId())
                .update();

        processor.processDue();
        assertThat(handler.timesHandled(credit)).isZero();

        clock.advance(Duration.ofSeconds(60));
        processor.processDue();
        assertThat(handler.timesHandled(credit)).isOne();
    }

    @Test
    void handsAFailedCreditOverAgainOnceItIsDue() {
        PixCredit credit = record();
        handler.script(credit, _ -> {
            throw new IllegalStateException("The database went away");
        });
        processor.processDue();
        handler.script(credit, _ -> CreditResult.RECORDED);

        clock.advance(Duration.ofSeconds(1));
        processor.processDue();

        assertThat(handler.timesHandled(credit)).isEqualTo(2);
        assertThat(row(credit)).containsEntry("status", "processed").containsEntry("attempts", 2);
    }

    @Test
    void neverGivesUpACredit() {
        PixCredit credit = record();
        handler.script(credit, _ -> {
            throw new IllegalStateException("The database went away");
        });
        for (int attempt = 1; attempt <= 12; attempt++) {
            processor.processDue();
            clock.advance(Duration.ofMinutes(5));
        }
        handler.script(credit, _ -> CreditResult.RECORDED);

        processor.processDue();

        assertThat(row(credit)).containsEntry("status", "processed").containsEntry("attempts", 13);
    }

    @Test
    void handsOverAtMostSixteenCreditsAPass() {
        List<PixCredit> credits = IntStream.range(0, 20).mapToObj(_ -> record()).toList();

        assertThat(processor.processDue()).isEqualTo(16);
        // Drains the rest, with whatever other tests of the class left due.
        for (int pass = 0; pass < 5 && processor.processDue() > 0; pass++) {}

        assertThat(credits).allSatisfy(credit -> {
            assertThat(handler.timesHandled(credit)).isOne();
            assertThat(row(credit)).containsEntry("status", "processed");
        });
    }

    @Test
    void reportsHowLongTheOldestPendingCreditHasWaited() {
        record();
        clock.advance(Duration.ofSeconds(90));

        assertThat(meters.get(PendingCredits.OLDEST_PENDING).gauge().value()).isGreaterThanOrEqualTo(90.0);
    }

    /** Records a new credit in the inbox, due at once, as the endpoint does. */
    private PixCredit record() {
        var credit = new PixCredit(
                "E99990011202601051200" + random(11),
                "M99990011" + random(23),
                random(25),
                Money.ofMinor(10_000, Currency.getInstance("BRL")),
                clock.instant().minusSeconds(1),
                "99990011");
        inbox.record(credit, "rails-2026-11", new byte[32]);
        return credit;
    }

    private Map<String, @Nullable Object> row(PixCredit credit) {
        return owner.sql("SELECT * FROM rails.pix_credits WHERE end_to_end_id = ?")
                .param(credit.endToEndId())
                .query()
                .singleRow();
    }

    private double unmatched() {
        return meters.get(CreditProcessor.UNMATCHED).counter().count();
    }

    private static String random(int length) {
        var text = new StringBuilder();
        for (int i = 0; i < length; i++) {
            text.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return text.toString();
    }
}

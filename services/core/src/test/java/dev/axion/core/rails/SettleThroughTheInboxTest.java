package dev.axion.core.rails;

import static dev.axion.core.billing.BillingFixtures.brl;
import static dev.axion.core.billing.BillingFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.billing.BillingApi;
import dev.axion.core.billing.BillingFixtures;
import dev.axion.core.billing.ChargeRequest;
import dev.axion.core.billing.ChargeStatus;
import dev.axion.core.billing.ChargeView;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.TenancyApi;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A credit notification on its whole way through the running core, with the inbox's processor on
 * its schedule (billing design, sections 7 and 8): signed by the rails, recorded in the inbox,
 * answered, then settled by billing without anyone running a pass by hand.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "axion.rails.processor.enabled=true")
@Import(PostgresTestConfiguration.class)
class SettleThroughTheInboxTest {

    private static final RailsSigner RAILS = new RailsSigner("rails-2026-11");

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    // Always three fraction digits, as the rails' contract asks; ISO_INSTANT drops zeros.
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        registry.add("axion.rails.signing.keys[0].id", RAILS::keyId);
        registry.add("axion.rails.signing.keys[0].public-key", RAILS::publicKey);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private BillingApi billing;

    @Autowired
    private TenancyApi tenancy;

    @Autowired
    private Clock clock;

    @Test
    void settlesTheChargeOfACreditTheRailsDelivered() throws InterruptedException {
        NewOrganization organization = tenancy.createOrganization("Acme", "subject-" + UUID.randomUUID());
        ChargeView charge = TenantContext.call(
                organization.test(),
                Environment.TEST,
                () -> created(billing.createCharge(ChargeRequest.of(brl(10_000)))));
        Instant acceptedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);

        var response = new RailsClient(port, RAILS)
                .post("/rails/pix/credits", notification(charge, acceptedAt), clock.instant());
        assertThat(response.statusCode()).isEqualTo(202);

        ChargeView paid = awaitPaid(organization, charge);
        assertThat(paid.paidAt()).isEqualTo(acceptedAt);
    }

    @Test
    void runsAPassEvery500Milliseconds(@Autowired ScheduledTaskHolder scheduler) {
        assertThat(scheduler.getScheduledTasks())
                .map(ScheduledTask::getTask)
                .filteredOn(task -> task.toString().endsWith("ScheduledCreditProcessing.processDue"))
                .singleElement()
                .isInstanceOfSatisfying(
                        IntervalTask.class,
                        task -> assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMillis(500)));
    }

    private ChargeView awaitPaid(NewOrganization organization, ChargeView charge) throws InterruptedException {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (true) {
            ChargeView now = TenantContext.call(
                            organization.test(), Environment.TEST, () -> billing.charge(charge.id()))
                    .orElseThrow()
                    .charge();
            if (now.status() == ChargeStatus.PAID || Instant.now().isAfter(deadline)) {
                assertThat(now.status())
                        .as("the charge's status after %s", PATIENCE)
                        .isEqualTo(ChargeStatus.PAID);
                return now;
            }
            Thread.sleep(100);
        }
    }

    /** The credit notification of the rails simulator design, section 5.2, paying the charge. */
    private static byte[] notification(ChargeView charge, Instant acceptedAt) {
        return """
                {
                  "messageId": "M99990011aB3dE5gH7jK2mN4pQ6rS8tU",
                  "messageType": "pacs.008",
                  "creationDateTime": "%2$s",
                  "endToEndId": "%1$s",
                  "transactionId": "%3$s",
                  "localInstrument": "QRDN",
                  "interbankSettlementAmount": { "amount": "100.00", "currency": "BRL" },
                  "acceptanceDateTime": "%2$s",
                  "debtor": { "name": "Ana Souza", "taxId": "52601815906" },
                  "debtorAccount": { "branch": "0001", "number": "123456", "type": "CACC" },
                  "debtorAgent": { "ispb": "99990011" },
                  "creditorAgent": { "ispb": "99990001" },
                  "creditorAccount": { "proxy": "a2b3bccd-2305-4632-9494-a795efa6fbb1" }
                }
                """.formatted(BillingFixtures.endToEndId(), TIMESTAMP.format(acceptedAt), charge.transactionId())
                .getBytes(StandardCharsets.UTF_8);
    }
}

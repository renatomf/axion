package dev.axion.core.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.events.EventFixtures;
import dev.axion.core.notifications.application.LiveStreamsTest.TestSink;
import dev.axion.core.shared.MemberId;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.webhooks.WebhookEndpointDisabled;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The domain events {@link StreamPublishing} hands to live streams after their transaction (events
 * and webhooks design, sections 3.3 and 10). Billing's are proven through the endpoint in {@code
 * EventStreamControllerTest}.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class StreamPublishingTest {

    private final LiveStreams streams;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;

    @Autowired
    StreamPublishingTest(LiveStreams streams, ApplicationEventPublisher events, TransactionTemplate transactions) {
        this.streams = streams;
        this.events = events;
        this.transactions = transactions;
    }

    @Test
    void handsADisabledEndpointToTheMembersWhoManageWebhooksAndNoOther() {
        WebhookEndpointDisabled disabled = EventFixtures.one(WebhookEndpointDisabled.class);
        TestSink manager = open(disabled, Set.of(Permission.CHARGES_READ, Permission.WEBHOOKS_MANAGE));
        TestSink reader = open(disabled, Set.of(Permission.CHARGES_READ));

        transactions.executeWithoutResult(status -> events.publishEvent(disabled));

        await().atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(manager.events())
                        .singleElement()
                        .satisfies(event -> {
                            assertThat(event.id()).isEqualTo(disabled.id());
                            assertThat(event.type()).isEqualTo("webhook_endpoint.disabled");
                        }));
        assertThat(reader.events()).isEmpty();
    }

    private TestSink open(WebhookEndpointDisabled event, Set<Permission> permissions) {
        TestSink sink = new TestSink(Integer.MAX_VALUE);
        streams.open(
                new StreamMember(event.tenant(), new MemberId(UUID.randomUUID()), permissions), sink, Optional.empty());
        return sink;
    }
}

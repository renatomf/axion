package dev.axion.core.webhooks.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.shared.EventId;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.domain.Attempt;
import dev.axion.core.webhooks.domain.SigningSecret;
import dev.axion.core.webhooks.domain.WebhookSignature;
import dev.axion.core.webhooks.http.SignedEvent;
import dev.axion.core.webhooks.http.WebhookClient;
import dev.axion.core.webhooks.persistence.ClaimedDelivery;
import dev.axion.core.webhooks.persistence.JdbcQueue;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the delivery queue (events and webhooks design, section 8.2). Each pass claims the due
 * deliveries it has room for in a short transaction, which commits before anything is sent; each is
 * then signed and sent on a virtual thread of its own, and its result recorded in a second short
 * transaction.
 * No transaction or connection stays open while a tenant's server answers, and a slow endpoint holds
 * at most {@value #PER_ENDPOINT} of the {@value #IN_FLIGHT} requests in flight.
 *
 * <p>A delivery has one attempt until retries arrive: it succeeds on a {@code 2xx}, and is dead
 * otherwise. One that cannot be signed is not sent: it keeps its lease until it passes, and is
 * claimed again.
 */
@Service
public class DeliveryQueue implements DisposableBean {

    static final int IN_FLIGHT = 20;

    static final int PER_ENDPOINT = 4;

    // Longer than a connection and a whole response together, so a lease outlives a working attempt.
    static final Duration LEASE = Duration.ofSeconds(30);

    private static final String ROLE = "axion_webhooks_jobs";

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryQueue.class);

    private final JdbcQueue queue;
    private final WebhookClient client;
    private final SecretCipher cipher;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Semaphore room = new Semaphore(IN_FLIGHT);
    private final ExecutorService senders = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("webhook-sender-", 1).factory());

    DeliveryQueue(
            JdbcQueue queue,
            WebhookClient client,
            SecretCipher cipher,
            ModuleRoles roles,
            TransactionTemplate transactions,
            Clock clock) {
        this.queue = queue;
        this.client = client;
        this.cipher = cipher;
        this.roles = roles;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Claims what is due and room allows, and starts sending it.
     *
     * @return the sends started, each done once its result is recorded
     */
    public List<Future<?>> drain() {
        int free = room.availablePermits();
        if (free == 0) {
            return List.of();
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<ClaimedDelivery> claimed = Objects.requireNonNull(transactions.execute(
                status -> roles.call(ROLE, () -> queue.claim(now, now.plus(LEASE), free, PER_ENDPOINT))));
        List<Future<?>> sends = new ArrayList<>(claimed.size());
        for (ClaimedDelivery delivery : claimed) {
            // Only this thread takes room, and it took no more than was free.
            room.acquireUninterruptibly();
            sends.add(senders.submit(() -> {
                try {
                    send(delivery);
                } finally {
                    room.release();
                }
            }));
        }
        return sends;
    }

    private void send(ClaimedDelivery delivery) {
        String event = new EventId(delivery.event()).publicId().toString();
        if (delivery.secrets().isEmpty()) {
            LOG.error(
                    "Webhook delivery {} of event {} to endpoint {} was not sent: the endpoint has no secret that signs",
                    delivery.id(),
                    event,
                    delivery.endpoint());
            return;
        }
        SignedEvent signed;
        try {
            signed = sign(delivery, event);
        } catch (GeneralSecurityException e) {
            LOG.error(
                    "Webhook delivery {} of event {} to endpoint {} was not sent: its secrets do not decrypt",
                    delivery.id(),
                    event,
                    delivery.endpoint(),
                    e);
            return;
        }
        Attempt attempt = client.send(delivery.url(), signed);
        String status = attempt.succeeded() ? "SUCCEEDED" : "DEAD";
        boolean recorded = Objects.requireNonNull(
                transactions.execute(transaction -> roles.call(ROLE, () -> queue.finish(delivery, status))));
        // The URL is left out: its path or query may hold the tenant's token.
        if (!recorded) {
            LOG.info(
                    "Webhook delivery {} of event {} changed during its attempt; its result was dropped",
                    delivery.id(),
                    event);
        } else if (attempt.succeeded()) {
            LOG.info("Delivered event {} to webhook endpoint {} ({})", event, delivery.endpoint(), attempt);
        } else {
            LOG.warn(
                    "Webhook delivery {} of event {} to endpoint {} failed: {}",
                    delivery.id(),
                    event,
                    delivery.endpoint(),
                    attempt);
        }
    }

    // Signed at the attempt's time, so a request sent again is signed afresh.
    private SignedEvent sign(ClaimedDelivery delivery, String event) throws GeneralSecurityException {
        var endpoint = new WebhookEndpointId(delivery.endpoint());
        List<SigningSecret> secrets = new ArrayList<>(delivery.secrets().size());
        for (byte[] ciphertext : delivery.secrets()) {
            secrets.add(cipher.decrypt(ciphertext, endpoint));
        }
        long timestamp = clock.instant().getEpochSecond();
        byte[] body = delivery.body().getBytes(StandardCharsets.UTF_8);
        return new SignedEvent(event, timestamp, WebhookSignature.header(secrets, event, timestamp, body), body);
    }

    @Override
    public void destroy() {
        // A delivery interrupted here keeps its lease until it passes, and is claimed again.
        senders.shutdownNow();
    }
}

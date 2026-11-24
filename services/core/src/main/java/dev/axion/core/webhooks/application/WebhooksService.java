package dev.axion.core.webhooks.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.TenantId;
import dev.axion.core.shared.UuidV7Generator;
import dev.axion.core.webhooks.CreateEndpointResult;
import dev.axion.core.webhooks.CreatedEndpoint;
import dev.axion.core.webhooks.DisabledReason;
import dev.axion.core.webhooks.EndpointChanges;
import dev.axion.core.webhooks.EndpointCursor;
import dev.axion.core.webhooks.EndpointPage;
import dev.axion.core.webhooks.EndpointRequest;
import dev.axion.core.webhooks.EndpointStatus;
import dev.axion.core.webhooks.EndpointView;
import dev.axion.core.webhooks.RollSecretResult;
import dev.axion.core.webhooks.RolledSecret;
import dev.axion.core.webhooks.UpdateEndpointResult;
import dev.axion.core.webhooks.WebhookEndpointCreated;
import dev.axion.core.webhooks.WebhookEndpointDeleted;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.WebhookEndpointUpdated;
import dev.axion.core.webhooks.WebhookSecretRolled;
import dev.axion.core.webhooks.WebhooksApi;
import dev.axion.core.webhooks.domain.EventTypes;
import dev.axion.core.webhooks.domain.SigningSecret;
import dev.axion.core.webhooks.domain.UrlValidation;
import dev.axion.core.webhooks.domain.WebhookUrlValidator;
import dev.axion.core.webhooks.persistence.JdbcDeliveries;
import dev.axion.core.webhooks.persistence.JdbcEndpoints;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhooksService implements WebhooksApi {

    private static final String ROLE = "axion_webhooks";

    private final JdbcEndpoints endpoints;
    private final JdbcDeliveries deliveries;
    private final WebhookUrlValidator urls;
    private final SecretCipher cipher;
    private final ModuleRoles roles;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final UuidV7Generator ids;
    private final SecureRandom random = new SecureRandom();

    WebhooksService(
            JdbcEndpoints endpoints,
            JdbcDeliveries deliveries,
            WebhookUrlValidator urls,
            SecretCipher cipher,
            ModuleRoles roles,
            ApplicationEventPublisher events,
            Clock clock) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.urls = urls;
        this.cipher = cipher;
        this.roles = roles;
        this.events = events;
        this.clock = clock;
        this.ids = new UuidV7Generator(clock);
    }

    @Override
    @Transactional
    public CreateEndpointResult create(EndpointRequest request) {
        requireLimits(request);
        TenantContext.Binding tenant = binding("Registering a webhook endpoint");
        // Before the lock: resolving the name may take seconds, and needs no row.
        if (urls.validate(request.url()) instanceof UrlValidation.Refused refused) {
            return new CreateEndpointResult.InvalidUrl(refused.reason());
        }
        CreateEndpointResult result = roles.call(ROLE, () -> createWithinLimit(tenant, request));
        // Published after the role switch returns: the registry is written with the login role
        // (events and webhooks design, section 4.2).
        if (result instanceof CreateEndpointResult.Created(CreatedEndpoint created)) {
            EndpointView endpoint = created.endpoint();
            events.publishEvent(new WebhookEndpointCreated(header(endpoint.createdAt()), endpoint));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public EndpointPage endpoints(EndpointCursor cursor, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size must be between 1 and " + MAX_PAGE_SIZE + ": " + limit);
        }
        TenantId tenant = bound("Listing webhook endpoints");
        return roles.call(ROLE, () -> page(tenant, cursor, limit));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EndpointView> endpoint(WebhookEndpointId id) {
        TenantId tenant = bound("Reading a webhook endpoint");
        return roles.call(ROLE, () -> endpoints.find(tenant, id));
    }

    @Override
    @Transactional
    public UpdateEndpointResult update(WebhookEndpointId id, EndpointChanges changes) {
        changes.url().ifPresent(WebhooksService::requireUrlLength);
        changes.description().ifPresent(WebhooksService::requireDescription);
        changes.enabledEvents().ifPresent(WebhooksService::requireEnabledEvents);
        TenantId tenant = bound("Changing a webhook endpoint");
        // Before the lock, as for a registration.
        if (changes.url().isPresent() && urls.validate(changes.url().get()) instanceof UrlValidation.Refused refused) {
            return new UpdateEndpointResult.InvalidUrl(refused.reason());
        }
        Optional<Change> change = roles.call(ROLE, () -> change(tenant, id, changes));
        if (change.isEmpty()) {
            return new UpdateEndpointResult.EndpointNotFound();
        }
        Change made = change.get();
        if (!made.endpoint().equals(made.previous())) {
            events.publishEvent(new WebhookEndpointUpdated(header(made.at()), made.endpoint(), made.previous()));
        }
        return new UpdateEndpointResult.Updated(made.endpoint());
    }

    @Override
    @Transactional
    public Optional<EndpointView> delete(WebhookEndpointId id) {
        TenantId tenant = bound("Deleting a webhook endpoint");
        Instant now = now();
        Optional<EndpointView> deleted = roles.call(ROLE, () -> {
            Optional<EndpointView> marked = endpoints.markDeleted(tenant, id, now);
            marked.ifPresent(endpoint -> deliveries.cancelPending(tenant, endpoint.id()));
            return marked;
        });
        deleted.ifPresent(endpoint -> events.publishEvent(new WebhookEndpointDeleted(header(now), endpoint)));
        return deleted;
    }

    @Override
    @Transactional
    public RollSecretResult rollSecret(WebhookEndpointId id, Duration grace) {
        requireGrace(grace);
        TenantId tenant = bound("Rolling a webhook secret");
        Roll roll = roles.call(ROLE, () -> roll(tenant, id, grace));
        if (roll.result() instanceof RollSecretResult.Rolled(RolledSecret rolled)) {
            events.publishEvent(
                    new WebhookSecretRolled(header(roll.at()), rolled.endpoint(), rolled.previousSecretExpiresAt()));
        }
        return roll.result();
    }

    // The endpoint's lock orders concurrent rolls, so only the first ends the current secret and the
    // others find its grace period.
    private Roll roll(TenantId tenant, WebhookEndpointId id, Duration grace) {
        Optional<EndpointView> locked = endpoints.lock(tenant, id);
        Instant now = now();
        if (locked.isEmpty()) {
            return new Roll(new RollSecretResult.EndpointNotFound(), now);
        }
        Optional<Instant> graceEnd = endpoints.secretGraceEnd(tenant, id, now);
        if (graceEnd.isPresent()) {
            return new Roll(new RollSecretResult.GracePeriodActive(graceEnd.get()), now);
        }
        Instant expiresAt = now.plus(grace);
        endpoints.endCurrentSecret(tenant, id, expiresAt);
        SigningSecret secret = SigningSecret.generate(random);
        endpoints.insertSecret(ids.next(), tenant, id, cipher.encrypt(secret, id), now);
        return new Roll(new RollSecretResult.Rolled(new RolledSecret(locked.get(), secret, expiresAt)), now);
    }

    private record Roll(RollSecretResult result, Instant at) {}

    // Locked, so concurrent changes apply one after another and each event's previous endpoint is the
    // one it changed.
    private Optional<Change> change(TenantId tenant, WebhookEndpointId id, EndpointChanges changes) {
        return endpoints.lock(tenant, id).map(previous -> {
            Instant now = now();
            EndpointView endpoint = changed(previous, changes);
            if (!endpoint.equals(previous)) {
                endpoints.update(tenant, endpoint, now);
            }
            // A disabled endpoint receives nothing, and enabled again it starts afresh (section 6.3).
            if (previous.status() == EndpointStatus.ENABLED && endpoint.status() == EndpointStatus.DISABLED) {
                deliveries.cancelPending(tenant, id);
            }
            if (previous.status() == EndpointStatus.DISABLED && endpoint.status() == EndpointStatus.ENABLED) {
                endpoints.startAfresh(tenant, id);
            }
            return new Change(endpoint, previous, now);
        });
    }

    private static EndpointView changed(EndpointView previous, EndpointChanges changes) {
        EndpointStatus status = changes.status().orElse(previous.status());
        Optional<DisabledReason> reason = switch (status) {
            case ENABLED -> Optional.empty();
            case DISABLED -> previous.disabledReason().or(() -> Optional.of(DisabledReason.BY_TENANT));
        };
        return new EndpointView(
                previous.id(),
                changes.url().orElse(previous.url()),
                changes.description().orElse(previous.description()),
                changes.enabledEvents().orElse(previous.enabledEvents()),
                status,
                reason,
                previous.createdAt());
    }

    private record Change(EndpointView endpoint, EndpointView previous, Instant at) {}

    // One endpoint more than the limit tells whether there is a next page, so no cursor leads to an empty one.
    private EndpointPage page(TenantId tenant, EndpointCursor cursor, int limit) {
        List<EndpointView> found = endpoints.list(tenant, cursor.after(), Math.addExact(limit, 1));
        if (found.size() <= limit) {
            return new EndpointPage(found, Optional.empty());
        }
        List<EndpointView> page = found.subList(0, limit);
        return new EndpointPage(
                page, Optional.of(EndpointCursor.after(page.getLast().id())));
    }

    private CreateEndpointResult createWithinLimit(TenantContext.Binding binding, EndpointRequest request) {
        TenantId tenant = binding.tenant();
        // Concurrent registrations are counted one after another, so they cannot pass the limit together.
        endpoints.lockTenantEndpoints(tenant);
        if (endpoints.count(tenant) >= MAX_ENDPOINTS) {
            return new CreateEndpointResult.LimitReached(MAX_ENDPOINTS);
        }
        Instant now = now();
        var endpoint = new EndpointView(
                new WebhookEndpointId(ids.next()),
                request.url(),
                request.description(),
                request.enabledEvents(),
                EndpointStatus.ENABLED,
                Optional.empty(),
                now);
        endpoints.insert(tenant, binding.environment(), endpoint);
        SigningSecret secret = SigningSecret.generate(random);
        endpoints.insertSecret(ids.next(), tenant, endpoint.id(), cipher.encrypt(secret, endpoint.id()), now);
        return new CreateEndpointResult.Created(new CreatedEndpoint(endpoint, secret));
    }

    private EventHeader header(Instant occurredAt) {
        return EventHeader.ofCurrentWork(new EventId(ids.next()), occurredAt);
    }

    // PostgreSQL keeps microseconds; a returned instant must match the stored row.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static TenantId bound(String work) {
        return binding(work).tenant();
    }

    private static TenantContext.Binding binding(String work) {
        return TenantContext.current().orElseThrow(() -> new IllegalStateException(work + " needs a tenant bound"));
    }

    // The schema checks the same rules; checking first gives a clear error, not a constraint name,
    // and refuses an oversized URL before its name is resolved.
    private static void requireLimits(EndpointRequest request) {
        requireUrlLength(request.url());
        requireDescription(request.description());
        requireEnabledEvents(request.enabledEvents());
    }

    private static void requireGrace(Duration grace) {
        if (grace.isNegative() || grace.compareTo(MAX_SECRET_GRACE) > 0) {
            throw new IllegalArgumentException(
                    "Grace period must be between zero and " + MAX_SECRET_GRACE + ": " + grace);
        }
    }

    private static void requireUrlLength(String url) {
        if (url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("A URL is at most " + MAX_URL_LENGTH + " characters");
        }
    }

    private static void requireDescription(Optional<String> description) {
        description.ifPresent(text -> {
            int length = text.codePointCount(0, text.length());
            if (length < 1 || length > MAX_DESCRIPTION_LENGTH) {
                throw new IllegalArgumentException("A description is 1 to " + MAX_DESCRIPTION_LENGTH + " characters");
            }
        });
    }

    private static void requireEnabledEvents(Set<String> types) {
        if (types.size() > MAX_EVENT_TYPES || !EventTypes.isValidSelection(types)) {
            throw new IllegalArgumentException("Enabled events must be 1 to " + MAX_EVENT_TYPES
                    + " webhook event types, or " + EventTypes.ALL + " alone");
        }
    }
}

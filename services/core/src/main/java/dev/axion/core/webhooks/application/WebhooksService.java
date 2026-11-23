package dev.axion.core.webhooks.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.TenantId;
import dev.axion.core.shared.UuidV7Generator;
import dev.axion.core.webhooks.CreateEndpointResult;
import dev.axion.core.webhooks.CreatedEndpoint;
import dev.axion.core.webhooks.EndpointCursor;
import dev.axion.core.webhooks.EndpointPage;
import dev.axion.core.webhooks.EndpointRequest;
import dev.axion.core.webhooks.EndpointStatus;
import dev.axion.core.webhooks.EndpointView;
import dev.axion.core.webhooks.WebhookEndpointCreated;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.WebhooksApi;
import dev.axion.core.webhooks.domain.EventTypes;
import dev.axion.core.webhooks.domain.SigningSecret;
import dev.axion.core.webhooks.domain.UrlValidation;
import dev.axion.core.webhooks.domain.WebhookUrlValidator;
import dev.axion.core.webhooks.persistence.JdbcEndpoints;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhooksService implements WebhooksApi {

    private static final String ROLE = "axion_webhooks";

    private final JdbcEndpoints endpoints;
    private final WebhookUrlValidator urls;
    private final SecretCipher cipher;
    private final ModuleRoles roles;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final UuidV7Generator ids;
    private final SecureRandom random = new SecureRandom();

    WebhooksService(
            JdbcEndpoints endpoints,
            WebhookUrlValidator urls,
            SecretCipher cipher,
            ModuleRoles roles,
            ApplicationEventPublisher events,
            Clock clock) {
        this.endpoints = endpoints;
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
        TenantId tenant = bound("Registering a webhook endpoint");
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

    private CreateEndpointResult createWithinLimit(TenantId tenant, EndpointRequest request) {
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
        endpoints.insert(tenant, endpoint);
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
        return TenantContext.current()
                .orElseThrow(() -> new IllegalStateException(work + " needs a tenant bound"))
                .tenant();
    }

    // The schema checks the same rules; checking first gives a clear error, not a constraint name,
    // and refuses an oversized URL before its name is resolved.
    private static void requireLimits(EndpointRequest request) {
        if (request.url().length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("A URL is at most " + MAX_URL_LENGTH + " characters");
        }
        request.description().ifPresent(description -> {
            int length = description.codePointCount(0, description.length());
            if (length < 1 || length > MAX_DESCRIPTION_LENGTH) {
                throw new IllegalArgumentException("A description is 1 to " + MAX_DESCRIPTION_LENGTH + " characters");
            }
        });
        if (request.enabledEvents().size() > MAX_EVENT_TYPES || !EventTypes.isValidSelection(request.enabledEvents())) {
            throw new IllegalArgumentException("Enabled events must be 1 to " + MAX_EVENT_TYPES
                    + " webhook event types, or " + EventTypes.ALL + " alone");
        }
    }
}

package dev.axion.core.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.access.ApiKeyIssued;
import dev.axion.core.access.ApiKeyRevoked;
import dev.axion.core.access.ApiKeyRolled;
import dev.axion.core.access.ApiKeyView;
import dev.axion.core.shared.Actor;
import dev.axion.core.shared.ApiKeyId;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.MemberId;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.Permission;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What each API key change records in the audit trail (events and webhooks design, section 9.4). */
class AuditedKeyEventsTest {

    private static final Instant NOW = Instant.parse("2026-11-24T15:04:05.123456Z");
    private static final List<String> SCOPES = List.of("charges:read", "charges:write");

    @Test
    void recordsAnIssuedKeyWithItsNameAndScopes() {
        ApiKeyView key = key(Optional.empty());

        assertThat(AuditedKeyEvents.issued(new ApiKeyIssued(header(), key)))
                .isEqualTo(new AuditedAction(
                        "api_key.issued",
                        "api_key",
                        key.id().publicId().toString(),
                        Map.of("name", "Checkout", "scopes", SCOPES)));
    }

    @Test
    void recordsARollOnTheRolledKeyWithItsReplacementAndTheEndOfItsGracePeriod() {
        ApiKeyView rolled = key(Optional.of(NOW.plusSeconds(86_400)));
        ApiKeyView replacement = key(Optional.empty());

        assertThat(AuditedKeyEvents.rolled(new ApiKeyRolled(header(), replacement, rolled)))
                .isEqualTo(new AuditedAction(
                        "api_key.rolled",
                        "api_key",
                        rolled.id().publicId().toString(),
                        Map.of(
                                "name",
                                "Checkout",
                                "scopes",
                                SCOPES,
                                "replacement",
                                replacement.id().publicId().toString(),
                                "expires_at",
                                "2026-11-25T15:04:05.123456Z")));
    }

    @Test
    void recordsARevokedKey() {
        ApiKeyView key = key(Optional.empty());

        assertThat(AuditedKeyEvents.revoked(new ApiKeyRevoked(header(), key)))
                .isEqualTo(new AuditedAction(
                        "api_key.revoked",
                        "api_key",
                        key.id().publicId().toString(),
                        Map.of("name", "Checkout", "scopes", SCOPES)));
    }

    private static EventHeader header() {
        return new EventHeader(
                new EventId(UUID.randomUUID()),
                new TenantId(UUID.randomUUID()),
                Environment.TEST,
                new Actor.Member(new MemberId(UUID.randomUUID())),
                "correlation-1",
                NOW);
    }

    private static ApiKeyView key(Optional<Instant> expiresAt) {
        return new ApiKeyView(
                new ApiKeyId(UUID.randomUUID()),
                "Checkout",
                "a1b2",
                Set.of(Permission.CHARGES_WRITE, Permission.CHARGES_READ),
                NOW,
                expiresAt,
                Optional.empty(),
                Optional.empty());
    }
}

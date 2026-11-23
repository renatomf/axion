package dev.axion.core.webhooks.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@link EndedSecretRemoval} against a real PostgreSQL. The owner commits the secrets, since the job
 * removes them in transactions of its own.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class EndedSecretRemovalTest {

    private final EndedSecretRemoval removal;
    private final DatabaseOwner owner;
    private final UUID tenant = UUID.randomUUID();
    private final UUID endpoint = UUID.randomUUID();

    @Autowired
    EndedSecretRemovalTest(EndedSecretRemoval removal, DatabaseOwner owner) {
        this.removal = removal;
        this.owner = owner;
    }

    @BeforeEach
    void insertEndpoint() {
        owner.jdbc().sql("""
                        INSERT INTO webhooks.endpoints
                               (id, tenant_id, url, enabled_events, status, created_at, updated_at)
                        VALUES (?, ?, 'https://orders.example.com/axion', '{charge.paid}', 'ENABLED', now(), now())
                        """).params(endpoint, tenant).update();
    }

    @Test
    void removesEndedSecretsInAsManyBatchesAsTheyTake() {
        int ended = 2 * EndedSecretRemoval.BATCH_SIZE + 500;
        insertSecrets(ended, "now() - interval '1 second'");

        removal.removeEnded();

        // Not the count removed: the scheduled run of the job may have removed some first.
        assertThat(secretsOfTheEndpoint()).isZero();
    }

    @Test
    void keepsTheCurrentSecretAndOneStillSigning() {
        insertSecrets(1, "NULL");
        insertSecrets(1, "now() + interval '1 hour'");
        insertSecrets(1, "now() - interval '1 second'");

        removal.removeEnded();

        assertThat(secretsOfTheEndpoint()).isEqualTo(2);
    }

    @Test
    void removesNothingWhenNoSecretEnded() {
        removal.removeEnded();

        assertThat(removal.removeEnded()).isZero();
    }

    // Created a day ago, so an end in the past still follows the creation.
    private void insertSecrets(int count, String expiresAt) {
        owner.jdbc()
                .sql("""
                        INSERT INTO webhooks.secrets (id, endpoint_id, tenant_id, ciphertext, expires_at, created_at)
                        SELECT gen_random_uuid(), ?, ?, '\\\\x00'::bytea, %s, now() - interval '1 day'
                          FROM generate_series(1, ?)
                        """.formatted(expiresAt))
                .params(endpoint, tenant, count)
                .update();
    }

    private long secretsOfTheEndpoint() {
        return Objects.requireNonNull(owner.jdbc()
                .sql("SELECT count(*) FROM webhooks.secrets WHERE endpoint_id = ?")
                .param(endpoint)
                .query(Long.class)
                .single());
    }
}

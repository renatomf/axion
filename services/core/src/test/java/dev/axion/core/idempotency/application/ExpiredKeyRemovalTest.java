package dev.axion.core.idempotency.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@link ExpiredKeyRemoval} against a real PostgreSQL. The owner commits the keys, since the job
 * removes them in transactions of its own.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ExpiredKeyRemovalTest {

    private final ExpiredKeyRemoval removal;
    private final DatabaseOwner owner;
    private final UUID tenant = UUID.randomUUID();

    @Autowired
    ExpiredKeyRemovalTest(ExpiredKeyRemoval removal, DatabaseOwner owner) {
        this.removal = removal;
        this.owner = owner;
    }

    @Test
    void removesExpiredKeysInAsManyBatchesAsTheyTake() {
        int expired = 2 * ExpiredKeyRemoval.BATCH_SIZE + 500;
        insertKeys(expired, "now() - interval '2 days'");

        assertThat(removal.removeExpired()).isGreaterThanOrEqualTo(expired);
        assertThat(keysOfTheTenant()).isZero();
    }

    @Test
    void keepsAKeyStillHonored() {
        insertKeys(1, "now() - interval '23 hours'");
        insertKeys(1, "now() - interval '25 hours'");

        removal.removeExpired();

        assertThat(keysOfTheTenant()).isOne();
    }

    @Test
    void removesNothingWhenNoKeyExpired() {
        removal.removeExpired();

        assertThat(removal.removeExpired()).isZero();
    }

    private void insertKeys(int count, String createdAt) {
        owner.jdbc().sql("""
                        INSERT INTO idempotency.keys
                               (tenant_id, key, fingerprint, response_status, content_type, response_body,
                                correlation_id, created_at, expires_at)
                        SELECT ?, 'key-' || gen_random_uuid(), sha256(n::text::bytea), 201, 'application/json',
                               '{}'::bytea, 'test', %1$s, %1$s + interval '24 hours'
                          FROM generate_series(1, ?) AS n
                        """.formatted(createdAt)).params(tenant, count).update();
    }

    private long keysOfTheTenant() {
        return Objects.requireNonNull(owner.jdbc()
                .sql("SELECT count(*) FROM idempotency.keys WHERE tenant_id = ?")
                .param(tenant)
                .query(Long.class)
                .single());
    }
}

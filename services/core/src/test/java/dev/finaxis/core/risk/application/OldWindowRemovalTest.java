package dev.finaxis.core.risk.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.PostgresTestConfiguration;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@link OldWindowRemoval} against a real PostgreSQL. The owner commits the windows, since the job
 * removes them in transactions of its own.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class OldWindowRemovalTest {

    private final OldWindowRemoval removal;
    private final DatabaseOwner owner;
    private final UUID tenant = UUID.randomUUID();

    @Autowired
    OldWindowRemovalTest(OldWindowRemoval removal, DatabaseOwner owner) {
        this.removal = removal;
        this.owner = owner;
    }

    @Test
    void removesOldWindowsInAsManyBatchesAsTheyTake() {
        int old = 2 * OldWindowRemoval.BATCH_SIZE + 500;
        insertHourlyWindows(old, "now() - interval '8 days'");

        assertThat(removal.removeOld()).isGreaterThanOrEqualTo(old);
        assertThat(windowsOfTheTenant()).isZero();
    }

    @Test
    void keepsAWindowOfTheLastSevenDays() {
        insertHourlyWindows(1, "now() - interval '6 days'");
        insertHourlyWindows(1, "now() - interval '8 days'");

        removal.removeOld();

        assertThat(windowsOfTheTenant()).isOne();
    }

    @Test
    void removesNothingWhenNoWindowIsOld() {
        removal.removeOld();

        assertThat(removal.removeOld()).isZero();
    }

    // One window per hour back from the first one, so each has a key of its own.
    private void insertHourlyWindows(int count, String firstStart) {
        owner.jdbc().sql("""
                        INSERT INTO risk.charge_volume (tenant_id, rule, window_start, total_minor, count)
                        SELECT ?, 'HOURLY_COUNT', date_trunc('hour', %s) - n * interval '1 hour', 100, 1
                          FROM generate_series(0, ? - 1) AS n
                        """.formatted(firstStart)).params(tenant, count).update();
    }

    private long windowsOfTheTenant() {
        return Objects.requireNonNull(owner.jdbc()
                .sql("SELECT count(*) FROM risk.charge_volume WHERE tenant_id = ?")
                .param(tenant)
                .query(Long.class)
                .single());
    }
}

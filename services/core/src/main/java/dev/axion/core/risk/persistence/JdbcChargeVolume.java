package dev.axion.core.risk.persistence;

import dev.axion.core.shared.TenantId;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcChargeVolume {

    private final JdbcClient jdbc;

    public JdbcChargeVolume(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds a charge to the tenant's window if the window stays within its limits, and answers whether
     * it did (billing design, section 6). The update holds the row's lock until the transaction ends;
     * a concurrent charge waits for it and is then checked against the total it committed, so two
     * charges never both take the last unit of a limit.
     */
    public boolean tryAdd(TenantId tenant, ChargeWindow window, long amount) {
        jdbc.sql("""
                        INSERT INTO risk.charge_volume (tenant_id, rule, window_start, total_minor, count)
                        VALUES (:tenant, :rule, :start, 0, 0)
                        ON CONFLICT DO NOTHING
                        """)
                .param("tenant", tenant.value())
                .param("rule", window.rule().name())
                .param("start", window.start().atOffset(ZoneOffset.UTC))
                .update();
        int added = jdbc.sql("""
                        UPDATE risk.charge_volume
                           SET total_minor = total_minor + :amount, count = count + 1
                         WHERE tenant_id = :tenant
                           AND rule = :rule
                           AND window_start = :start
                           AND total_minor + :amount <= :maxTotal
                           AND count + 1 <= :maxCount
                        """)
                .param("tenant", tenant.value())
                .param("rule", window.rule().name())
                .param("start", window.start().atOffset(ZoneOffset.UTC))
                .param("amount", amount)
                .param("maxTotal", window.maxTotal())
                .param("maxCount", window.maxCount())
                .update();
        return added == 1;
    }

    /**
     * Deletes up to {@code limit} windows that started before {@code cutoff}, of every tenant; the
     * removal job's policies let it see windows older than 7 days only. Returns how many it deleted.
     */
    public int deleteStartedBefore(Instant cutoff, int limit) {
        return jdbc.sql("""
                        DELETE FROM risk.charge_volume
                         WHERE (tenant_id, rule, window_start) IN (SELECT tenant_id, rule, window_start
                                                                     FROM risk.charge_volume
                                                                    WHERE window_start < :cutoff
                                                                    LIMIT :limit)
                        """)
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .update();
    }
}

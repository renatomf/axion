package dev.axion.simulator.spi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PaymentRepository {

    private final JdbcClient jdbc;

    PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records the payment, unless its EndToEndId is taken: then nothing is written and the answer is false. */
    boolean insertIfAbsent(Payment payment) {
        int inserted = jdbc.sql("""
                        INSERT INTO payments (end_to_end_id, transaction_id, amount_minor, debtor_ispb, br_code, settled_at)
                        VALUES (:endToEndId, :transactionId, :amountMinor, :debtorIspb, :brCode, :settledAt)
                        ON CONFLICT (end_to_end_id) DO NOTHING
                        """)
                .param("endToEndId", payment.endToEndId().value())
                .param("transactionId", payment.transactionId())
                .param("amountMinor", payment.amountMinor())
                .param("debtorIspb", payment.debtorInstitution().digits())
                .param("brCode", payment.brCode())
                .param("settledAt", OffsetDateTime.ofInstant(payment.settledAt(), ZoneOffset.UTC))
                .update();
        return inserted == 1;
    }

    Optional<Payment> find(EndToEndId endToEndId) {
        return jdbc.sql("""
                        SELECT end_to_end_id, transaction_id, amount_minor, debtor_ispb, br_code, settled_at
                        FROM payments
                        WHERE end_to_end_id = ?
                        """)
                .param(endToEndId.value())
                .query(PaymentRepository::payment)
                .optional();
    }

    private static Payment payment(ResultSet row, int rowNumber) throws SQLException {
        return new Payment(
                new EndToEndId(row.getString("end_to_end_id")),
                row.getString("transaction_id"),
                row.getLong("amount_minor"),
                new Ispb(row.getString("debtor_ispb")),
                row.getString("br_code"),
                row.getObject("settled_at", OffsetDateTime.class).toInstant());
    }
}

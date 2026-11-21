package dev.finaxis.simulator.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.simulator.PostgresTestConfiguration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The constraints of the payments table (rails simulator design, section 8.3), as the simulator's role sees them. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PaymentsTableTest {

    private static final OffsetDateTime SETTLED_AT = OffsetDateTime.of(2026, 11, 21, 14, 30, 12, 0, ZoneOffset.UTC);

    private final JdbcClient jdbc;

    @Autowired
    PaymentsTableTest(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void acceptsAValidPayment() {
        insert("E99990011202611211430aaaaaaaaaa1", "fx7Kq2mP9wR4tY6uA8sD3fG5h", 10_000, "99990011");

        assertThat(jdbc.sql("SELECT amount_minor FROM payments WHERE end_to_end_id = ?")
                        .param("E99990011202611211430aaaaaaaaaa1")
                        .query(Long.class)
                        .single())
                .isEqualTo(10_000L);
    }

    @Test
    void refusesASecondPaymentWithTheSameEndToEndId() {
        insert("E99990011202611211430aaaaaaaaaa2", "tx1", 100, "99990011");

        assertThatThrownBy(() -> insert("E99990011202611211430aaaaaaaaaa2", "tx2", 200, "99990012"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "X99990011202611211430aaaaaaaaaa3", // not starting with E
                "E9999001A202611211430aaaaaaaaaa3", // ISPB with a letter
                "E99990011202611211430aaaaaaaaa-3", // a character that is neither a letter nor a digit
                "E99990011202611211430aaaaaaaaa3 " // 31 characters, padded to 32
            })
    void refusesAMalformedEndToEndId(String endToEndId) {
        assertThatThrownBy(() -> insert(endToEndId, "tx", 100, "99990011"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc-123", "a234567890123456789012345X"})
    void refusesATransactionIdThatIsNotOneTo25LettersOrDigits(String transactionId) {
        assertThatThrownBy(() -> insert("E99990011202611211430aaaaaaaaaa4", transactionId, 100, "99990011"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void refusesAnAmountThatIsNotPositive(long amountMinor) {
        assertThatThrownBy(() -> insert("E99990011202611211430aaaaaaaaaa5", "tx", amountMinor, "99990011"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAnIspbThatIsNotEightDigits() {
        assertThatThrownBy(() -> insert("E99990011202611211430aaaaaaaaaa6", "tx", 100, "9999001A"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insert(String endToEndId, String transactionId, long amountMinor, String debtorIspb) {
        jdbc.sql("""
                        INSERT INTO payments (end_to_end_id, transaction_id, amount_minor, debtor_ispb, br_code, settled_at)
                        VALUES (?, ?, ?, ?, '000201', ?)
                        """)
                .params(endToEndId, transactionId, amountMinor, debtorIspb, SETTLED_AT)
                .update();
    }
}

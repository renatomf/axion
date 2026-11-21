package dev.finaxis.core.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class FingerprintTest {

    private static final byte[] BODY = "{\"amount\":1000}".getBytes(StandardCharsets.UTF_8);

    @Test
    void isTheSha256OfThePathALineFeedAndTheBody() throws Exception {
        var expected = MessageDigest.getInstance("SHA-256")
                .digest("/v1/charges\n{\"amount\":1000}".getBytes(StandardCharsets.UTF_8));

        assertThat(Fingerprint.of("/v1/charges", BODY).value()).isEqualTo(expected);
    }

    @Test
    void isTheSameForTheSameRequest() {
        assertThat(Fingerprint.of("/v1/charges", BODY)).isEqualTo(Fingerprint.of("/v1/charges", BODY.clone()));
        assertThat(Fingerprint.of("/v1/charges", BODY).hashCode())
                .isEqualTo(Fingerprint.of("/v1/charges", BODY.clone()).hashCode());
    }

    @Test
    void changesWithThePath() {
        assertThat(Fingerprint.of("/v1/charges/ch_1/refunds", BODY))
                .isNotEqualTo(Fingerprint.of("/v1/charges/ch_2/refunds", BODY));
    }

    @Test
    void changesWithAnyByteOfTheBody() {
        var original = Fingerprint.of("/v1/charges", BODY);
        for (int i = 0; i < BODY.length; i++) {
            var changed = BODY.clone();
            changed[i] ^= 1;

            assertThat(Fingerprint.of("/v1/charges", changed)).as("byte %d", i).isNotEqualTo(original);
        }
    }

    @Test
    void changesWithWhitespaceInTheBody() {
        var spaced = "{\"amount\": 1000}".getBytes(StandardCharsets.UTF_8);

        assertThat(Fingerprint.of("/v1/charges", spaced)).isNotEqualTo(Fingerprint.of("/v1/charges", BODY));
    }

    @Test
    void rejectsAValueThatIsNotSha256() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Fingerprint(new byte[16]));
    }

    @Test
    void cannotBeChangedThroughItsBytes() {
        var bytes = new byte[Fingerprint.LENGTH];
        var fingerprint = new Fingerprint(bytes);
        bytes[0] = 1;
        fingerprint.value()[1] = 1;

        assertThat(fingerprint.value()).isEqualTo(new byte[Fingerprint.LENGTH]);
    }

    @Test
    void printsAsHex() {
        var bytes = new byte[Fingerprint.LENGTH];
        bytes[0] = (byte) 0xAB;

        assertThat(new Fingerprint(bytes)).hasToString(HexFormat.of().formatHex(bytes));
    }
}

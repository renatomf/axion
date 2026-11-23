package dev.axion.core.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CapturedResponseTest {

    private static final byte[] BODY = "{\"payer\":\"Maria\"}".getBytes(StandardCharsets.UTF_8);

    @ParameterizedTest
    @ValueSource(ints = {99, 600})
    void rejectsAStatusThatIsNotHttp(int status) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CapturedResponse(status, "application/json", null, BODY));
    }

    @Test
    void rejectsABlankContentType() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CapturedResponse(201, " ", null, BODY));
    }

    @Test
    void cannotBeChangedThroughItsBody() {
        var body = BODY.clone();
        var response = new CapturedResponse(201, "application/json", null, body);
        body[0] = 'x';
        response.body()[1] = 'x';

        assertThat(response.body()).isEqualTo(BODY);
    }

    @Test
    void equalsAResponseWithTheSameBytes() {
        assertThat(new CapturedResponse(201, "application/json", "/v1/charges/ch_1", BODY))
                .isEqualTo(new CapturedResponse(201, "application/json", "/v1/charges/ch_1", BODY.clone()))
                .isNotEqualTo(new CapturedResponse(201, "application/json", null, BODY));
    }

    @Test
    void printsTheBodysLengthInsteadOfTheBody() {
        assertThat(new CapturedResponse(201, "application/json", null, BODY).toString())
                .doesNotContain("Maria")
                .contains(BODY.length + " bytes");
    }
}

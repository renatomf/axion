package dev.finaxis.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PublicIdTest {

    /** Vectors from the TypeID specification, so the encoding matches other implementations. */
    @ParameterizedTest
    @CsvSource({
        "00000000-0000-0000-0000-000000000000, ch_00000000000000000000000000",
        "01890a5d-ac96-774b-bcce-b302099a8057, ch_01h455vb4pex5vsknk084sn02q",
        "0110c853-1d09-52d8-d73e-1194e95b5f19, ch_0123456789abcdefghjkmnpqrs",
        "ffffffff-ffff-ffff-ffff-ffffffffffff, ch_7zzzzzzzzzzzzzzzzzzzzzzzzz",
    })
    void encodesTheUuidInLowercaseCrockfordBase32(String uuid, String text) {
        PublicId id = new PublicId("ch", UUID.fromString(uuid));

        assertThat(id).hasToString(text);
        assertThat(PublicId.parse("ch", text)).isEqualTo(id);
    }

    @Test
    void parsesBackToTheSameUuid() {
        UUID uuid = UUID.fromString("01890a5d-ac96-774b-bcce-b302099a8057");

        PublicId parsed = PublicId.parse("acct", new PublicId("acct", uuid).toString());

        assertThat(parsed.prefix()).isEqualTo("acct");
        assertThat(parsed.uuid()).isEqualTo(uuid);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "pay_01h455vb4pex5vsknk084sn02q", // another resource type
                "ch01h455vb4pex5vsknk084sn02q", // no underscore
                "ch_01H455VB4PEX5VSKNK084SN02Q", // uppercase
                "ch_01h455vb4pex5vsknk084sn02", // one character short
                "ch_01h455vb4pex5vsknk084sn02qq", // one character long
                "ch_01h455vb4pex5vsknk084sn0iq", // i, a Crockford look-alike of 1
                "ch_01h455vb4pex5vsknk084sn0oq", // o, a Crockford look-alike of 0
                "ch_01h455vb4pex5vsknk084sn0uq", // u, not in the alphabet
                "ch_80000000000000000000000000", // above 128 bits
                "ch_01h455vb4pex5vsknk084sn0-q", // punctuation
                "",
            })
    void rejectsAnythingButTheExactForm(String text) {
        assertThatThrownBy(() -> PublicId.parse("ch", text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prefix ch");
    }

    @Test
    void doesNotEchoRejectedInput() {
        String text = "ch_<script>alert(1)</script>";

        assertThatThrownBy(() -> PublicId.parse("ch", text)).hasMessageNotContaining("<script>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Ch", "ch_x", "c1", "toolongpx"})
    void rejectsAnInvalidPrefix(String prefix) {
        UUID uuid = UUID.fromString("01890a5d-ac96-774b-bcce-b302099a8057");

        assertThatThrownBy(() -> new PublicId(prefix, uuid)).isInstanceOf(IllegalArgumentException.class);
    }
}

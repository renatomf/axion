package dev.axion.core.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChainHashTest {

    @Test
    void startsAChainWithThirtyTwoZeroBytes() {
        assertThat(ChainHash.ZERO.bytes()).hasSize(32).containsOnly(0);
    }

    @Test
    void refusesBytesThatAreNotASha256Hash() {
        assertThatThrownBy(() -> ChainHash.of(new byte[31])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsItsBytesFromTheCallersChanges() {
        byte[] bytes = new byte[32];
        var hash = ChainHash.of(bytes);

        bytes[0] = 1;
        hash.bytes()[1] = 1;

        assertThat(hash).isEqualTo(ChainHash.ZERO);
    }

    @Test
    void showsItsBytesAsLowercaseHex() {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) 0xAB;

        assertThat(ChainHash.of(bytes)).hasToString("ab" + "00".repeat(31));
    }
}

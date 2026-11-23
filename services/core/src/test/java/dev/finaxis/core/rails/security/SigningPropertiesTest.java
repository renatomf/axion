package dev.finaxis.core.rails.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.rails.RailsSigner;
import java.util.List;
import org.junit.jupiter.api.Test;

class SigningPropertiesTest {

    private final RailsSigner current = new RailsSigner("rails-2026-11");
    private final RailsSigner previous = new RailsSigner("rails-2026-10");

    @Test
    void holdsEachKeyByItsId() {
        var signing = new SigningProperties(List.of(
                new SigningProperties.Key(current.keyId(), current.publicKey()),
                new SigningProperties.Key(previous.keyId(), previous.publicKey())));

        assertThat(signing.publicKeys()).containsOnlyKeys("rails-2026-11", "rails-2026-10");
    }

    @Test
    void refusesNoKeys() {
        assertThatThrownBy(() -> new SigningProperties(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one key");
    }

    @Test
    void refusesTheSameIdTwice() {
        var key = new SigningProperties.Key(current.keyId(), current.publicKey());

        assertThatThrownBy(() -> new SigningProperties(
                        List.of(key, new SigningProperties.Key(current.keyId(), previous.publicKey()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("each key ID once");
    }

    @Test
    void refusesAnIdThatASignatureCouldNotCarry() {
        assertThatThrownBy(() -> new SigningProperties.Key("rails\"2026", current.publicKey()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAKeyThatIsNotAnEd25519PublicKey() {
        assertThatThrownBy(() -> new SigningProperties.Key(current.keyId(), "bm90LWEta2V5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ed25519 public key");
    }
}

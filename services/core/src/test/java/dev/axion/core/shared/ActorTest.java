package dev.axion.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ActorTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void namesAJobByItsHyphenatedName() {
        assertThat(new Actor.Job("charge-expiry").name()).isEqualTo("charge-expiry");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Charge-expiry", "charge_expiry", "charge-", "-expiry", "charge expiry"})
    void refusesAJobNameThatIsNotHyphenatedLowercaseWords(String name) {
        assertThatThrownBy(() -> new Actor.Job(name)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsBackEachKindOfActorFromJson() {
        Actor member = new Actor.Member(new MemberId(UUID.randomUUID()));
        Actor key = new Actor.ApiKey(new ApiKeyId(UUID.randomUUID()));
        Actor job = new Actor.Job("pix-settlement");

        assertThat(json.readValue(json.writeValueAsString(member), Actor.class)).isEqualTo(member);
        assertThat(json.readValue(json.writeValueAsString(key), Actor.class)).isEqualTo(key);
        assertThat(json.readValue(json.writeValueAsString(job), Actor.class)).isEqualTo(job);
    }
}

package dev.finaxis.core.billing;

import static dev.finaxis.core.billing.BillingFixtures.brl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.shared.Money;
import java.time.Duration;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChargeRequestTest {

    private static final Duration ONE_DAY = Duration.ofDays(1);

    @Test
    void acceptsTheLimitsOfEveryField() {
        var metadata = new HashMap<String, String>();
        for (int i = 0; i < 20; i++) {
            metadata.put(String.format("%02d", i) + "k".repeat(38), "v".repeat(500));
        }

        var request = new ChargeRequest(brl(100_000_000), "ã".repeat(140), Duration.ofDays(7), metadata);

        assertThat(request.metadata()).hasSize(20);
    }

    @Test
    void acceptsTheShortestLifetime() {
        assertThat(new ChargeRequest(brl(1), "a", Duration.ofSeconds(60), Map.of()).expiresIn())
                .isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void expiresAfterADayByDefault() {
        var request = ChargeRequest.of(brl(10_000));

        assertThat(request.expiresIn()).isEqualTo(Duration.ofDays(1));
        assertThat(request.description()).isNull();
        assertThat(request.metadata()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 100_000_001})
    void refusesAnAmountOutsideTheChargeLimits(long minorUnits) {
        assertRefused(brl(minorUnits), null, ONE_DAY, Map.of(), "amount is 1 to 100000000");
    }

    @Test
    void refusesAnotherCurrency() {
        assertRefused(Money.ofMinor(10_000, Currency.getInstance("USD")), null, ONE_DAY, Map.of(), "BRL");
    }

    @Test
    void refusesAnEmptyDescription() {
        assertRefused(brl(10_000), "", ONE_DAY, Map.of(), "description is 1 to 140");
    }

    @Test
    void refusesADescriptionLongerThan140Characters() {
        assertRefused(brl(10_000), "ã".repeat(141), ONE_DAY, Map.of(), "not 141");
    }

    @Test
    void countsADescriptionByCodePoints() {
        String emoji = "💰";

        assertThat(new ChargeRequest(brl(10_000), emoji.repeat(140), ONE_DAY, Map.of()).description())
                .hasSize(280);
    }

    @ParameterizedTest
    @ValueSource(longs = {59, 604_801})
    void refusesALifetimeOutsideTheLimits(long seconds) {
        assertRefused(brl(10_000), null, Duration.ofSeconds(seconds), Map.of(), "expires PT1M to PT168H");
    }

    @Test
    void refusesMoreThan20MetadataKeys() {
        var metadata = new HashMap<String, String>();
        for (int i = 0; i < 21; i++) {
            metadata.put("key" + i, "value");
        }

        assertRefused(brl(10_000), null, ONE_DAY, metadata, "at most 20 keys");
    }

    @Test
    void refusesAnEmptyMetadataKey() {
        assertRefused(brl(10_000), null, ONE_DAY, Map.of("", "value"), "key is 1 to 40");
    }

    @Test
    void refusesAMetadataKeyLongerThan40Characters() {
        assertRefused(brl(10_000), null, ONE_DAY, Map.of("k".repeat(41), "value"), "key is 1 to 40");
    }

    @Test
    void refusesAMetadataValueLongerThan500CharactersWithoutEchoingIt() {
        String value = "secret".repeat(84);

        assertThatThrownBy(() -> new ChargeRequest(brl(10_000), null, ONE_DAY, Map.of("note", value)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value is at most 500")
                .hasMessageNotContaining("secret");
    }

    @Test
    void keepsACopyOfTheMetadata() {
        var metadata = new HashMap<String, String>(Map.of("order_id", "ord_8842"));
        var request = new ChargeRequest(brl(10_000), null, ONE_DAY, metadata);

        metadata.put("other", "value");

        assertThat(request.metadata()).containsOnlyKeys("order_id");
    }

    private static void assertRefused(
            Money amount,
            @Nullable String description,
            Duration expiresIn,
            Map<String, String> metadata,
            String message) {
        assertThatThrownBy(() -> new ChargeRequest(amount, description, expiresIn, metadata))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }
}

package dev.axion.core.billing.domain;

import static dev.axion.core.billing.BillingFixtures.brl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.shared.Money;
import dev.axion.core.shared.PixKey;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The expected codes were encoded, and their CRCs computed, by Python's {@code binascii.crc_hqx}. */
class BrCodeTest {

    /** The static code of the BCB's BR Code manual. */
    private static final String MANUAL_EXAMPLE = "00020126580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-426655440000"
            + "5204000053039865802BR5913Fulano de Tal6008BRASILIA62070503***63041D3D";

    private static final PixKey RANDOM_KEY = PixKey.parse("123e4567-e12b-12d1-a456-426655440000");

    @Test
    void computesTheCrcOfTheManualExample() {
        String covered = MANUAL_EXAMPLE.substring(0, MANUAL_EXAMPLE.length() - 4);

        assertThat(BrCode.crc16(covered)).isEqualTo(0x1D3D);
    }

    @Test
    void encodesTheManualMerchantWithAnAmountAndATransactionId() {
        var code = BrCode.encode(
                RANDOM_KEY, "Fulano de Tal", "BRASILIA", brl(10_000), new TransactionId("fx7Kq2mP9wR4tY6uA8sD3fG5h"));

        assertThat(code.text())
                .isEqualTo("00020101021226580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-426655440000"
                        + "5204000053039865406100.005802BR5913Fulano de Tal6008BRASILIA"
                        + "62290525fx7Kq2mP9wR4tY6uA8sD3fG5h63043251");
    }

    @Test
    void encodesAnEmailKeyAndAnAccentedName() {
        var code = BrCode.encode(
                PixKey.parse("fulano@example.com"),
                "Padaria São João Ltda.",
                "SAO PAULO",
                brl(123_456),
                new TransactionId("A1b2C3d4E5f6G7h8I9j0K1l2M"));

        assertThat(code.text())
                .isEqualTo("00020101021226400014br.gov.bcb.pix0118fulano@example.com"
                        + "52040000530398654071234.565802BR5921Padaria Sao Joao Ltda6009SAO PAULO"
                        + "62290525A1b2C3d4E5f6G7h8I9j0K1l2M63041771");
    }

    @Test
    void encodesOneCentavoForAMerchantWithNothingLeftOfItsName() {
        var code = BrCode.encode(
                PixKey.parse("+5561999999999"),
                "日本",
                "SAO PAULO",
                brl(1),
                new TransactionId("0000000000000000000000001"));

        assertThat(code.text())
                .isEqualTo("00020101021226360014br.gov.bcb.pix0114+55619999999995204000053039865404"
                        + "0.015802BR5905AXION6009SAO PAULO622905250000000000000000000000001"
                        + "6304489F");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Café Açaí & Cia.                | Cafe Acai Cia",
                "  Lojas   Ñandú  |  Lojas Nandu",
                "Comércio de Peças Automotivas Brasileiras | Comercio de Pecas Automot",
                "Distribuidora Paulista d Alimentos | Distribuidora Paulista d",
                "ACME-42 Ltda | ACME42 Ltda"
            })
    void normalizesTheOrganizationsName(String organizationName, String merchantName) {
        assertThat(BrCode.merchantName(organizationName)).isEqualTo(merchantName);
    }

    @ParameterizedTest
    @ValueSource(strings = {"日本", "!!!", "   ", "€ ß Æ"})
    void namesAxionWhenNothingOfTheNameIsLeft(String organizationName) {
        assertThat(BrCode.merchantName(organizationName)).isEqualTo("AXION");
    }

    @Test
    void cutsTheCityAt15Characters() {
        var code = BrCode.encode(RANDOM_KEY, "Fulano", "São José dos Campos", brl(100), transactionId());

        assertThat(code.text()).contains("6015Sao Jose dos Ca62");
    }

    @Test
    void refusesACityWithNothingLeft() {
        assertThatThrownBy(() -> BrCode.encode(RANDOM_KEY, "Fulano", "—", brl(100), transactionId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("merchant city");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -100})
    void refusesAnAmountThatIsNotPositive(long minorUnits) {
        assertThatThrownBy(() -> BrCode.encode(RANDOM_KEY, "Fulano", "BRASILIA", brl(minorUnits), transactionId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive amount");
    }

    @Test
    void refusesAnotherCurrency() {
        Money dollars = Money.ofMinor(100, Currency.getInstance("USD"));

        assertThatThrownBy(() -> BrCode.encode(RANDOM_KEY, "Fulano", "BRASILIA", dollars, transactionId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BRL");
    }

    private static TransactionId transactionId() {
        return new TransactionId("fx7Kq2mP9wR4tY6uA8sD3fG5h");
    }
}

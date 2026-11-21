package dev.finaxis.simulator.payer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BrCodeTest {

    /** The static code of the BCB's BR Code manual: a valid CRC, but no amount and no transaction ID. */
    private static final String MANUAL_EXAMPLE = "00020126580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-426655440000"
            + "5204000053039865802BR5913Fulano de Tal6008BRASILIA62070503***63041D3D";

    /** A code the simulator pays, its CRC computed by an independent implementation (Python's binascii.crc_hqx). */
    static final String PAYABLE = "00020101021226580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-426655440000"
            + "5204000053039865406100.005802BR5913Fulano de Tal6008BRASILIA62290525fx7Kq2mP9wR4tY6uA8sD3fG5h63043251";

    private static final String TRANSACTION_ID = "fx7Kq2mP9wR4tY6uA8sD3fG5h";

    @Test
    void computesTheCrcOfTheManualExample() {
        String covered = MANUAL_EXAMPLE.substring(0, MANUAL_EXAMPLE.length() - 4);

        assertThat(BrCode.crc16(covered)).isEqualTo(0x1D3D);
    }

    @Test
    void readsTheAmountAndTheTransactionId() {
        var code = BrCode.read(PAYABLE);

        assertThat(code.amountMinor()).isEqualTo(10_000);
        assertThat(code.transactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(code.text()).isEqualTo(PAYABLE);
    }

    @Test
    void readsThePixKey() {
        assertThat(BrCode.read(PAYABLE).pixKey()).isEqualTo("123e4567-e12b-12d1-a456-426655440000");
    }

    @Test
    void readsACodeWithoutAPixKey() {
        var code = codeWith(Map.of("26", tlv("00", "br.gov.bcb.pix") + tlv("25", "pix.example.com/qr/v2/cobv/1")));

        assertThat(BrCode.read(code).pixKey()).isNull();
    }

    @Test
    void refusesTheManualExampleForItsMissingAmount() {
        assertRefused(MANUAL_EXAMPLE, "amount (tag 54) is missing");
    }

    @Test
    void acceptsACrcWrittenInLowerCase() {
        String code = codeWith(Map.of("54", "12.00"));
        String lowerCaseCrc = code.substring(0, code.length() - 4)
                + code.substring(code.length() - 4).toLowerCase(Locale.ROOT);

        assertThat(BrCode.read(lowerCaseCrc).amountMinor()).isEqualTo(1_200);
    }

    @Test
    void acceptsTheGuiInUpperCase() {
        var code = codeWith(Map.of("26", "0014BR.GOV.BCB.PIX0136123e4567-e12b-12d1-a456-426655440000"));

        assertThat(BrCode.read(code).transactionId()).isEqualTo(TRANSACTION_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"100", "1.5", "0.01", "9999999999.99"})
    void readsAmountsWithUpToTwoFractionDigits(String amount) {
        var code = codeWith(Map.of("54", amount));

        assertThat(BrCode.read(code).amountMinor())
                .isEqualTo(new BigDecimal(amount).movePointRight(2).longValueExact());
    }

    @Test
    void refusesACrcThatDoesNotMatch() {
        String tampered = PAYABLE.replace("5406100.00", "5406900.00");

        assertRefused(tampered, "CRC (tag 63) does not match");
    }

    @Test
    void refusesACodeThatDoesNotEndWithItsCrc() {
        String withoutCrc = PAYABLE.substring(0, PAYABLE.length() - 8);

        assertRefused(withoutCrc, "does not end with its CRC");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0002", "000201260", "00AB01", "0002010102"})
    void refusesWhatCannotBeReadAsTagsLengthsAndValues(String text) {
        assertRefused(text, "cannot be read");
    }

    @Test
    void refusesARepeatedTag() {
        String body = "000201" + "5303986" + "5303986" + "6304";

        assertRefused(withCrc(body), "cannot be read");
    }

    @Test
    void refusesACodeLongerThan512Characters() {
        assertRefused("0".repeat(513), "longer than 512");
    }

    @Test
    void refusesAPayloadFormatOtherThan01() {
        assertRefused(codeWith(Map.of("00", "02")), "payload format indicator");
    }

    @Test
    void refusesAMerchantAccountWithoutThePixGui() {
        assertRefused(codeWith(Map.of("26", "0014br.gov.bcb.xyz")), "Pix GUI");
    }

    @Test
    void refusesACodeWithoutAMerchantAccount() {
        assertRefused(codeWithout("26"), "Pix GUI");
    }

    @Test
    void refusesACurrencyOtherThanTheReal() {
        assertRefused(codeWith(Map.of("53", "840")), "currency (tag 53)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.234", "1,00", "-1.00", "1e3", "12345678901.00"})
    void refusesAnAmountThatIsNotADecimalWithTwoFractionDigitsAtMost(String amount) {
        assertRefused(codeWith(Map.of("54", amount)), "amount (tag 54) is not a decimal");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00"})
    void refusesAZeroAmount(String amount) {
        assertRefused(codeWith(Map.of("54", amount)), "amount (tag 54) is not positive");
    }

    @Test
    void refusesACodeWithoutATransactionId() {
        assertRefused(codeWith(Map.of("62", "0703abc")), "transaction ID (tag 62, subfield 05) is missing");
        assertRefused(codeWithout("62"), "transaction ID (tag 62, subfield 05) is missing");
    }

    @Test
    void refusesTheTransactionIdOfAStaticCode() {
        assertRefused(codeWith(Map.of("62", "0503***")), "is ***");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc-123", "a234567890123456789012345X"})
    void refusesATransactionIdThatIsNotOneTo25LettersOrDigits(String transactionId) {
        assertRefused(codeWith(Map.of("62", tlv("05", transactionId))), "not 1 to 25 letters or digits");
    }

    private static void assertRefused(String text, String reason) {
        assertThatThrownBy(() -> BrCode.read(text))
                .isInstanceOf(InvalidBrCodeException.class)
                .hasMessageContaining(reason);
    }

    /** The fields of {@link #PAYABLE}, in its order, before the CRC. */
    private static Map<String, String> payableFields() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("00", "01");
        fields.put("01", "12");
        fields.put("26", "0014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-426655440000");
        fields.put("52", "0000");
        fields.put("53", "986");
        fields.put("54", "100.00");
        fields.put("58", "BR");
        fields.put("59", "Fulano de Tal");
        fields.put("60", "BRASILIA");
        fields.put("62", tlv("05", TRANSACTION_ID));
        return fields;
    }

    private static String codeWith(Map<String, String> changes) {
        var fields = payableFields();
        fields.putAll(changes);
        return encode(fields);
    }

    private static String codeWithout(String tag) {
        var fields = payableFields();
        fields.remove(tag);
        return encode(fields);
    }

    private static String encode(Map<String, String> fields) {
        var body = new StringBuilder();
        fields.forEach((tag, value) -> body.append(tlv(tag, value)));
        return withCrc(body.append("6304").toString());
    }

    private static String withCrc(String body) {
        return body + HexFormat.of().withUpperCase().toHexDigits((short) BrCode.crc16(body));
    }

    private static String tlv(String tag, String value) {
        return tag + "%02d".formatted(value.length()) + value;
    }
}

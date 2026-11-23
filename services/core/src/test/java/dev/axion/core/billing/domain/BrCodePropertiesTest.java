package dev.axion.core.billing.domain;

import static dev.axion.core.billing.BillingFixtures.brl;
import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.shared.PixKey;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.StringLength;

/**
 * Properties of every BR Code (billing design, section 13), over generated keys, names, cities,
 * amounts and transaction IDs: whatever the organization is called, the code can be read.
 */
class BrCodePropertiesTest {

    private static final List<String> TAGS = List.of("00", "01", "26", "52", "53", "54", "58", "59", "60", "62", "63");

    @Property(tries = 1000)
    void everyCodeHasItsTagsInOrderAndAValidCrc(
            @ForAll("keys") PixKey key,
            @ForAll @StringLength(min = 1, max = 100) String organizationName,
            @ForAll("cities") String city,
            @ForAll @LongRange(min = 1, max = Charge.MAX_AMOUNT_MINOR) long amountMinor,
            @ForAll @AlphaChars @NumericChars @StringLength(min = 1, max = 25) String transactionId) {
        String text = BrCode.encode(key, organizationName, city, brl(amountMinor), new TransactionId(transactionId))
                .text();
        List<Field> fields = parse(text);

        assertThat(text).hasSizeLessThanOrEqualTo(512).matches("\\p{ASCII}+");
        assertThat(fields).extracting(Field::tag).containsExactlyElementsOf(TAGS);
        assertThat(parse(value(fields, "26")))
                .containsExactly(new Field("00", "br.gov.bcb.pix"), new Field("01", key.value()));
        assertThat(new BigDecimal(value(fields, "54")).movePointRight(2).longValueExact())
                .isEqualTo(amountMinor);
        assertThat(value(fields, "59")).matches("[A-Za-z0-9]([A-Za-z0-9 ]{0,23}[A-Za-z0-9])?");
        assertThat(value(fields, "60")).matches("[A-Za-z0-9]([A-Za-z0-9 ]{0,13}[A-Za-z0-9])?");
        assertThat(parse(value(fields, "62"))).containsExactly(new Field("05", transactionId));

        String covered = text.substring(0, text.length() - 4);
        assertThat(covered).endsWith("6304");
        assertThat(text.substring(text.length() - 4))
                .isEqualTo(HexFormat.of().withUpperCase().toHexDigits((short) BrCode.crc16(covered)));
    }

    @Provide
    Arbitrary<PixKey> keys() {
        return Arbitraries.oneOf(
                Arbitraries.create(UUID::randomUUID).map(uuid -> new PixKey(PixKey.Type.RANDOM, uuid.toString())),
                Arbitraries.strings()
                        .withCharRange('a', 'z')
                        .ofMinLength(1)
                        .ofMaxLength(65)
                        .map(local -> new PixKey(PixKey.Type.EMAIL, local + "@example.com")),
                Arbitraries.just(PixKey.parse("+5561999999999")));
    }

    /** Cities with at least one letter after any noise; a city with none is refused. */
    @Provide
    Arbitrary<String> cities() {
        return Arbitraries.strings()
                .all()
                .ofMaxLength(30)
                .flatMap(noise -> Arbitraries.of("Brasília", "SAO PAULO", "São José dos Campos", "Ji-Paraná")
                        .map(city -> noise + " " + city));
    }

    private static String value(List<Field> fields, String tag) {
        return fields.stream()
                .filter(field -> field.tag().equals(tag))
                .findFirst()
                .orElseThrow()
                .value();
    }

    private static List<Field> parse(String text) {
        var fields = new ArrayList<Field>();
        int position = 0;
        while (position < text.length()) {
            int length = Integer.parseInt(text.substring(position + 2, position + 4));
            fields.add(new Field(
                    text.substring(position, position + 2), text.substring(position + 4, position + 4 + length)));
            position += 4 + length;
        }
        return fields;
    }

    private record Field(String tag, String value) {}
}

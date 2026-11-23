package dev.axion.core.billing.domain;

import dev.axion.core.shared.Money;
import dev.axion.core.shared.PixKey;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HexFormat;

/**
 * The text of a charge's QR code: the EMV Merchant-Presented Mode payload of the Central Bank's BR
 * Code manual, with the amount and the transaction ID in the code itself (billing design, section 5).
 */
public record BrCode(String text) {

    /** The most that the merchant name, tag 59, holds. */
    public static final int MERCHANT_NAME_LENGTH = 25;

    /** The most that the merchant city, tag 60, holds. */
    public static final int MERCHANT_CITY_LENGTH = 15;

    /** The receiving participant, named when nothing of the organization's name can be written. */
    static final String FALLBACK_MERCHANT_NAME = "AXION";

    private static final String PIX_GUI = "br.gov.bcb.pix";
    private static final String SINGLE_PAYMENT = "12";
    private static final String NO_CATEGORY = "0000";
    private static final String REAL = "986";
    private static final String BRAZIL = "BR";
    private static final String CRC_TAG_AND_LENGTH = "6304";
    private static final int MAX_VALUE_LENGTH = 99;

    /**
     * Encodes the code a payer's app reads for a charge. The organization's name and the city are
     * normalized to what the format allows (section 5.3).
     *
     * @throws IllegalArgumentException if the amount is not a positive amount in reais, or nothing of
     *     the city can be written, a bug in the caller or the configuration
     */
    public static BrCode encode(
            PixKey key, String organizationName, String city, Money amount, TransactionId transactionId) {
        if (!amount.currency().equals(Charge.CURRENCY) || !amount.isPositive()) {
            throw new IllegalArgumentException("A BR Code is for a positive amount in " + Charge.CURRENCY);
        }
        String merchantCity = normalize(city, MERCHANT_CITY_LENGTH);
        if (merchantCity.isEmpty()) {
            throw new IllegalArgumentException("The merchant city has no letters or digits to write");
        }
        String payload = field("00", "01")
                + field("01", SINGLE_PAYMENT)
                + field("26", field("00", PIX_GUI) + field("01", key.value()))
                + field("52", NO_CATEGORY)
                + field("53", REAL)
                + field("54", amount.toMajorUnits().toPlainString())
                + field("58", BRAZIL)
                + field("59", merchantName(organizationName))
                + field("60", merchantCity)
                + field("62", field("05", transactionId.value()))
                + CRC_TAG_AND_LENGTH;
        return new BrCode(payload + HexFormat.of().withUpperCase().toHexDigits((short) crc16(payload)));
    }

    /** The organization's name as tag 59 holds it, or {@value #FALLBACK_MERCHANT_NAME} if nothing is left. */
    static String merchantName(String organizationName) {
        String name = normalize(organizationName, MERCHANT_NAME_LENGTH);
        return name.isEmpty() ? FALLBACK_MERCHANT_NAME : name;
    }

    /** Accents removed, anything but ASCII letters, digits and single spaces dropped, cut at the limit. */
    private static String normalize(String text, int maxLength) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9 ]", "")
                .replaceAll(" +", " ")
                .strip();
        return plain.length() <= maxLength
                ? plain
                : plain.substring(0, maxLength).stripTrailing();
    }

    private static String field(String tag, String value) {
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("Tag " + tag + " holds at most " + MAX_VALUE_LENGTH + " characters");
        }
        return tag + (value.length() < 10 ? "0" : "") + value.length() + value;
    }

    /** CRC-16/CCITT-FALSE: polynomial 0x1021, initial value 0xFFFF, over the UTF-8 bytes, as the BR Code manual defines it. */
    static int crc16(String text) {
        int crc = 0xFFFF;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
            crc &= 0xFFFF;
        }
        return crc;
    }
}

package dev.axion.simulator.payer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A BR Code the simulator can pay: the EMV Merchant-Presented Mode payload of the Central Bank's BR
 * Code manual, with the amount and the transaction ID it carries (rails simulator design, section 4.2).
 *
 * <p>The amount and the transaction ID are read from tags 54 and 62-05, where an official dynamic code
 * carries only a location to fetch them from: a deviation listed in section 9 of the design.
 *
 * @param text the code as the payer read it
 * @param amountMinor the amount in centavos
 * @param transactionId the transaction ID, by which the core finds the charge
 * @param pixKey the Pix key of tag 26, subfield 01, if the code carries one
 */
public record BrCode(
        String text,
        long amountMinor,
        String transactionId,
        @Nullable String pixKey) {

    /** The EMV limit for a QR code payload. */
    private static final int MAX_LENGTH = 512;

    private static final String PAYLOAD_FORMAT = "00";
    private static final String MERCHANT_ACCOUNT = "26";
    private static final String CURRENCY = "53";
    private static final String AMOUNT = "54";
    private static final String ADDITIONAL_DATA = "62";
    private static final String CRC = "63";

    private static final String GUI = "00";
    private static final String PIX_KEY = "01";
    private static final String TRANSACTION_ID = "05";

    private static final String PIX_GUI = "br.gov.bcb.pix";
    private static final String REAL = "986";
    private static final String NO_TRANSACTION_ID = "***";

    private static final Pattern AMOUNT_FORMAT = Pattern.compile("[0-9]{1,10}(\\.[0-9]{1,2})?");
    private static final Pattern TRANSACTION_ID_FORMAT = Pattern.compile("[A-Za-z0-9]{1,25}");

    /**
     * Reads a BR Code, checking its structure, its CRC and the rules of section 4.2 of the design.
     *
     * @throws InvalidBrCodeException naming the first rule the code breaks
     */
    public static BrCode read(String text) {
        if (text.length() > MAX_LENGTH) {
            throw new InvalidBrCodeException("The BR Code is longer than " + MAX_LENGTH + " characters");
        }
        List<Field> fields = parse(text);
        checkCrc(text, fields);

        if (!fields.getFirst().tag().equals(PAYLOAD_FORMAT)
                || !fields.getFirst().value().equals("01")) {
            throw new InvalidBrCodeException("The payload format indicator (tag 00) is not first, or not 01");
        }
        Map<String, String> tags = byTag(fields);

        String merchantAccountText = tags.get(MERCHANT_ACCOUNT);
        Map<String, String> merchantAccount =
                merchantAccountText == null ? Map.of() : byTag(parse(merchantAccountText));
        String gui = merchantAccount.get(GUI);
        if (gui == null || !gui.equalsIgnoreCase(PIX_GUI)) {
            throw new InvalidBrCodeException("The merchant account information (tag 26) lacks the Pix GUI " + PIX_GUI);
        }
        if (!REAL.equals(tags.get(CURRENCY))) {
            throw new InvalidBrCodeException("The currency (tag 53) is not " + REAL + ", the real");
        }
        return new BrCode(
                text,
                amountMinor(tags.get(AMOUNT)),
                transactionId(tags.get(ADDITIONAL_DATA)),
                merchantAccount.get(PIX_KEY));
    }

    private static long amountMinor(@Nullable String amount) {
        if (amount == null) {
            throw new InvalidBrCodeException("The amount (tag 54) is missing");
        }
        if (!AMOUNT_FORMAT.matcher(amount).matches()) {
            throw new InvalidBrCodeException("The amount (tag 54) is not a decimal with at most two fraction digits");
        }
        long minor = new BigDecimal(amount).movePointRight(2).longValueExact();
        if (minor <= 0) {
            throw new InvalidBrCodeException("The amount (tag 54) is not positive");
        }
        return minor;
    }

    private static String transactionId(@Nullable String additionalData) {
        String transactionId =
                additionalData == null ? null : byTag(parse(additionalData)).get(TRANSACTION_ID);
        if (transactionId == null) {
            throw new InvalidBrCodeException("The transaction ID (tag 62, subfield 05) is missing");
        }
        if (transactionId.equals(NO_TRANSACTION_ID)) {
            throw new InvalidBrCodeException(
                    "The transaction ID (tag 62, subfield 05) is ***, so the payment could not be matched to a charge");
        }
        if (!TRANSACTION_ID_FORMAT.matcher(transactionId).matches()) {
            throw new InvalidBrCodeException(
                    "The transaction ID (tag 62, subfield 05) is not 1 to 25 letters or digits");
        }
        return transactionId;
    }

    /** The CRC16 of tag 63 must be the last field and match everything before its value, its own tag and length included. */
    private static void checkCrc(String text, List<Field> fields) {
        Field last = fields.getLast();
        if (!last.tag().equals(CRC) || last.value().length() != 4) {
            throw new InvalidBrCodeException("The BR Code does not end with its CRC (tag 63)");
        }
        String covered = text.substring(0, text.length() - last.value().length());
        String expected = HexFormat.of().withUpperCase().toHexDigits((short) crc16(covered));
        if (!expected.equalsIgnoreCase(last.value())) {
            throw new InvalidBrCodeException("The CRC (tag 63) does not match the BR Code");
        }
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

    /** Splits a payload, or a template's value, into two-digit tags, two-digit lengths and values. */
    private static List<Field> parse(String data) {
        var fields = new ArrayList<Field>();
        int position = 0;
        while (position < data.length()) {
            if (position + 4 > data.length()) {
                throw unreadable();
            }
            String tag = data.substring(position, position + 2);
            String length = data.substring(position + 2, position + 4);
            if (!isDigits(tag) || !isDigits(length)) {
                throw unreadable();
            }
            int end = position + 4 + Integer.parseInt(length);
            if (end > data.length()) {
                throw unreadable();
            }
            fields.add(new Field(tag, data.substring(position + 4, end)));
            position = end;
        }
        if (fields.isEmpty()) {
            throw unreadable();
        }
        return fields;
    }

    private static Map<String, String> byTag(List<Field> fields) {
        var tags = new HashMap<String, String>();
        for (Field field : fields) {
            if (tags.putIfAbsent(field.tag(), field.value()) != null) {
                throw unreadable();
            }
        }
        return tags;
    }

    private static boolean isDigits(String text) {
        return text.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static InvalidBrCodeException unreadable() {
        return new InvalidBrCodeException("The BR Code cannot be read as tags, lengths and values");
    }

    private record Field(String tag, String value) {}
}

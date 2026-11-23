package dev.axion.core.shared;

/** The two modulus 11 check digits that CPF and CNPJ numbers end with. */
final class CheckDigits {

    private CheckDigits() {}

    /**
     * Whether the last two characters are the check digits of the ones before them. Each character
     * counts as its code minus {@code '0'}, so CNPJ letters count as 17 to 42. One repeated
     * character has correct check digits but is never issued, so it is rejected.
     */
    static boolean isValid(String text, int maxWeight) {
        if (text.chars().allMatch(c -> c == text.charAt(0))) {
            return false;
        }
        int[] values = text.chars().map(c -> c - '0').toArray();
        int body = values.length - 2;
        return mod11(values, body, maxWeight) == values[body] && mod11(values, body + 1, maxWeight) == values[body + 1];
    }

    // Weights from 2 at the right, restarting after maxWeight; 11 minus the remainder, or 0 for 0 and 1.
    private static int mod11(int[] values, int length, int maxWeight) {
        int sum = 0;
        int weight = 2;
        for (int i = length - 1; i >= 0; i--) {
            sum += values[i] * weight;
            weight = weight == maxWeight ? 2 : weight + 1;
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }
}

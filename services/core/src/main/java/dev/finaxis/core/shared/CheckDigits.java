package dev.finaxis.core.shared;

/** The modulus 11 check digit that CPF and CNPJ numbers end with. */
final class CheckDigits {

    private CheckDigits() {}

    /**
     * The check digit of the first {@code length} values.
     *
     * <p>Each value is multiplied by a weight: 2 for the rightmost value, growing by one to the left
     * and starting again at 2 after {@code maxWeight}. The check digit is 11 minus the remainder of
     * the sum divided by 11, or 0 when that remainder is 0 or 1.
     */
    static int mod11(int[] values, int length, int maxWeight) {
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

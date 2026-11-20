package dev.finaxis.core.shared;

/** The modulus 11 check digit that CPF and CNPJ numbers end with. */
final class CheckDigits {

    private CheckDigits() {}

    // Weights from 2 at the right, restarting after maxWeight; 11 minus the remainder, or 0 for 0 and 1.
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

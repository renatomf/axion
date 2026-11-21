package dev.finaxis.simulator.payer;

/** The CPF, the tax ID of a person in Brazil: nine digits and two check digits computed modulo 11. */
final class Cpf {

    private Cpf() {}

    static boolean isValid(String cpf) {
        if (!cpf.matches("[0-9]{11}") || cpf.chars().distinct().count() == 1) {
            return false;
        }
        return checkDigit(cpf, 9) == cpf.charAt(9) - '0' && checkDigit(cpf, 10) == cpf.charAt(10) - '0';
    }

    /** The digit at {@code position}: the first {@code position} digits weighted from {@code position + 1} down to 2. */
    private static int checkDigit(String cpf, int position) {
        int sum = 0;
        for (int i = 0; i < position; i++) {
            sum += (cpf.charAt(i) - '0') * (position + 1 - i);
        }
        return sum * 10 % 11 % 10;
    }
}

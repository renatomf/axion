package dev.axion.simulator.spi;

/**
 * The payer, as the credit notification names them. Personal data: synthetic, but never logged and kept
 * only inside the message (rails simulator design, sections 5.1 and 8.3).
 *
 * @param name the payer's name
 * @param taxId the payer's CPF, 11 digits
 */
public record Debtor(String name, String taxId) {

    @Override
    public String toString() {
        return "Debtor[redacted]";
    }
}

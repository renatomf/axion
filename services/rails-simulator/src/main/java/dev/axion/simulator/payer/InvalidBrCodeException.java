package dev.axion.simulator.payer;

/** A BR Code the simulated payer's institution refuses to pay. The message names the rule, never the code's content. */
public class InvalidBrCodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InvalidBrCodeException(String reason) {
        super(reason);
    }
}

package dev.axion.simulator.operator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How operators authenticate (rails simulator design, section 8.2).
 *
 * @param token the bearer token every operator request carries; at least 32 characters
 */
@ConfigurationProperties("simulator.operator")
record OperatorProperties(String token) {

    static final int MIN_TOKEN_LENGTH = 32;

    OperatorProperties {
        if (token.length() < MIN_TOKEN_LENGTH) {
            // The token itself is never echoed.
            throw new IllegalArgumentException(
                    "simulator.operator.token must have at least " + MIN_TOKEN_LENGTH + " characters");
        }
    }

    @Override
    public String toString() {
        return "OperatorProperties[token=***]";
    }
}

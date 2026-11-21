package dev.finaxis.simulator.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The simulator's signing key (rails simulator design, section 6.4). Configuration only, never in the
 * repository; the core holds the public half.
 *
 * @param keyId the name the core finds the public key by, sent in each signature
 * @param privateKey the Ed25519 private key, PKCS #8 DER in base64
 */
@ConfigurationProperties("simulator.signing")
record SigningProperties(String keyId, String privateKey) {

    @Override
    public String toString() {
        return "SigningProperties[keyId=" + keyId + ", privateKey=<redacted>]";
    }
}

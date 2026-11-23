package dev.axion.simulator;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Gives every test context the same Ed25519 key, generated once per test run, so no private key is ever
 * written to the repository (rails simulator design, section 6.4). It sets the variables {@code
 * application.yml} reads, as an environment does. Registered in {@code META-INF/spring.factories} of the
 * test resources.
 */
public class TestSigningKey implements EnvironmentPostProcessor {

    public static final String KEY_ID = "rails-test";

    private static final KeyPair PAIR = generate();

    /** The public half, to verify what the simulator signed. */
    public static PublicKey publicKey() {
        return PAIR.getPublic();
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment
                .getPropertySources()
                .addLast(new MapPropertySource(
                        "testSigningKey",
                        Map.of(
                                "RAILS_SIMULATOR_SIGNING_KEY_ID",
                                KEY_ID,
                                "RAILS_SIMULATOR_SIGNING_PRIVATE_KEY",
                                Base64.getEncoder()
                                        .encodeToString(PAIR.getPrivate().getEncoded()))));
    }

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

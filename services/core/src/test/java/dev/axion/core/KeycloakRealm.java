package dev.axion.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;

/**
 * The {@code axion} realm of the test Keycloak, and tokens from it. The console's login (code
 * flow, PKCE, second factor) cannot run in a test, so this adds two password-grant clients and a
 * synthetic member from {@code keycloak/test-fixtures.json}: {@value #TEST_CLIENT}, which the core
 * accepts, and {@value #OTHER_CLIENT}, which it does not. Their tokens differ from the console's
 * only in {@code azp}.
 */
public final class KeycloakRealm {

    static final String ADMIN_USERNAME = "admin";
    static final String ADMIN_PASSWORD = "admin-test-only";

    public static final String TEST_CLIENT = "axion-test";
    public static final String OTHER_CLIENT = "axion-other";

    private static final String MEMBER_USERNAME = "member.test";
    private static final String MEMBER_PASSWORD = "synthetic-password-1";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    public record Tokens(String accessToken, String idToken) {}

    private final String baseUrl;
    private final RestClient http;

    KeycloakRealm(GenericContainer<?> keycloak) {
        this.baseUrl =
                "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(KeycloakTestConfiguration.HTTP_PORT);
        this.http = RestClient.create(baseUrl);
        addTestFixtures();
    }

    /** The realm's issuer: in {@code start-dev}, the URL tokens are requested from. */
    public String issuer() {
        return baseUrl + "/realms/axion";
    }

    /** Logs the synthetic member in through {@code client}, as the password grant allows. */
    public Tokens login(String client) {
        Map<String, Object> response = token(
                "/realms/axion",
                form(
                        "grant_type", "password",
                        "client_id", client,
                        "username", MEMBER_USERNAME,
                        "password", MEMBER_PASSWORD,
                        // openid asks for an ID token too.
                        "scope", "openid"));
        return new Tokens(String.valueOf(response.get("access_token")), String.valueOf(response.get("id_token")));
    }

    /** One partial import: the realm keeps its own settings and gains the fixtures. */
    private void addTestFixtures() {
        String adminToken = String.valueOf(token(
                        "/realms/master",
                        form(
                                "grant_type",
                                "password",
                                "client_id",
                                "admin-cli",
                                "username",
                                ADMIN_USERNAME,
                                "password",
                                ADMIN_PASSWORD))
                .get("access_token"));
        http.post()
                .uri("/admin/realms/axion/partialImport")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(readFixtures())
                .retrieve()
                .toBodilessEntity();
    }

    private Map<String, Object> token(String realmPath, MultiValueMap<String, String> form) {
        Map<String, Object> response = http.post()
                .uri(realmPath + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JSON_OBJECT);
        if (response == null) {
            throw new IllegalStateException("Keycloak returned no token");
        }
        return response;
    }

    private static MultiValueMap<String, String> form(String... namesAndValues) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            form.add(namesAndValues[i], namesAndValues[i + 1]);
        }
        return form;
    }

    private static String readFixtures() {
        try {
            return new ClassPathResource("keycloak/test-fixtures.json").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package dev.axion.core.webhooks.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Each rule of section 7.1, against a resolver of the test that knows a few names. */
class WebhookUrlValidatorTest {

    private static final Map<String, List<String>> RECORDS = Map.of(
            "example.com", List.of("93.184.215.14", "2606:2800:21f:cb07:6820:80da:af6b:8b2c"),
            "example.com.", List.of("93.184.215.14"),
            "metadata.example.com", List.of("169.254.169.254"),
            "rebind.example.com", List.of("93.184.215.14", "10.0.0.5"),
            "mapped.example.com", List.of("::ffff:127.0.0.1"),
            "empty.example.com", List.of());

    private final List<String> lookups = new ArrayList<>();
    private final WebhookUrlValidator validator = new WebhookUrlValidator(this::resolve);

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://example.com/axion/webhooks",
                "https://EXAMPLE.com/hooks?source=axion",
                "https://example.com./hooks",
                "https://example.com:443/hooks",
                "https://example.com:1024/hooks",
                "https://example.com:8443/hooks",
                "https://example.com:65535/hooks"
            })
    void acceptsAnHttpsUrlToAPublicName(String url) {
        assertThat(validator.validate(url)).isEqualTo(new UrlValidation.Accepted(URI.create(url)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not a url",
                "https:example.com",
                "https:///hooks",
                "https://user@example.com/hooks",
                "https://user:password@example.com/hooks",
                "https://example.com/hooks#fragment",
                "https://exam_ple.com/hooks",
                "https://ex%61mple.com/hooks",
                "https://-example.com/hooks",
                "https://example.1com/hooks",
                "https://127.1/hooks",
                "https://0x7f.0.0.1/hooks"
            })
    void refusesAMalformedUrl(String url) {
        assertThat(validator.validate(url)).isEqualTo(refused(UrlRefusal.MALFORMED));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"http://example.com/hooks", "ftp://example.com/hooks", "example.com/hooks", "//example.com"})
    void refusesASchemeOtherThanHttps(String url) {
        assertThat(validator.validate(url)).isEqualTo(refused(UrlRefusal.MUST_BE_HTTPS));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://93.184.215.14/hooks",
                "https://169.254.169.254/latest/meta-data",
                "https://[::1]/hooks",
                "https://[::ffff:127.0.0.1]/hooks",
                "https://2130706433/hooks"
            })
    void refusesAnIpAddressInAnyNotation(String url) {
        assertThat(validator.validate(url)).isEqualTo(refused(UrlRefusal.MUST_USE_DOMAIN_NAME));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://localhost/hooks",
                "https://LOCALHOST./hooks",
                "https://api.localhost/hooks",
                "https://printer.local/hooks",
                "https://metadata.google.internal/computeMetadata/v1",
                "https://nas.home.arpa/hooks",
                "https://metadata/hooks",
                "https://intranet/hooks"
            })
    void refusesANameThatIsLocalByDefinition(String url) {
        assertThat(validator.validate(url)).isEqualTo(refused(UrlRefusal.MUST_BE_PUBLIC));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"https://example.com:80/hooks", "https://example.com:22/hooks", "https://example.com:1023/hooks"
            })
    void refusesAPrivilegedPortOtherThan443(String url) {
        assertThat(validator.validate(url)).isEqualTo(refused(UrlRefusal.PORT_NOT_ALLOWED));
    }

    @Test
    void refusesANameThatResolvesToTheMetadataService() {
        assertThat(validator.validate("https://metadata.example.com/hooks"))
                .isEqualTo(refused(UrlRefusal.MUST_BE_PUBLIC));
    }

    /** One private address is enough: the connection could be made to any of them. */
    @Test
    void refusesANameWithAnyAddressThatIsNotPublic() {
        assertThat(validator.validate("https://rebind.example.com/hooks"))
                .isEqualTo(refused(UrlRefusal.MUST_BE_PUBLIC));
    }

    @Test
    void refusesANameThatResolvesToAnIpv4AddressInAnIpv6Form() {
        assertThat(validator.validate("https://mapped.example.com/hooks"))
                .isEqualTo(refused(UrlRefusal.MUST_BE_PUBLIC));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://unknown.example.com/hooks", "https://empty.example.com/hooks"})
    void refusesANameThatDoesNotResolve(String url) {
        assertThat(validator.validate(url)).isEqualTo(refused(UrlRefusal.MUST_RESOLVE));
    }

    /** A name the rules refuse is never sent to the resolver, so a URL cannot make the core look it up. */
    @Test
    void looksUpNoNameARuleAlreadyRefused() {
        List.of(
                        "http://example.com/hooks",
                        "https://localhost/hooks",
                        "https://127.1/hooks",
                        "https://example.com:80/hooks",
                        "https://user@example.com/hooks")
                .forEach(validator::validate);

        assertThat(lookups).isEmpty();
    }

    private List<InetAddress> resolve(String host) throws UnknownHostException {
        lookups.add(host);
        List<String> literals = RECORDS.get(host);
        if (literals == null) {
            throw new UnknownHostException(host);
        }
        List<InetAddress> addresses = new ArrayList<>();
        for (String literal : literals) {
            addresses.add(InetAddress.getByName(literal));
        }
        return addresses;
    }

    private static UrlValidation refused(UrlRefusal reason) {
        return new UrlValidation.Refused(reason);
    }
}

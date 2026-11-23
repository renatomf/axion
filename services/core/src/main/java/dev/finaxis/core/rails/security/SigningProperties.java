package dev.finaxis.core.rails.security;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * The public keys the rails sign with, by key ID (rails simulator design, section 6.4). The core
 * holds no private key, so nothing in its configuration can sign a credit. A rotation lists both
 * keys until no delivery signed with the old one can still be retried.
 *
 * @param keys at least one key, each ID once
 */
@ConfigurationProperties("finaxis.rails.signing")
record SigningProperties(List<Key> keys) {

    SigningProperties {
        keys = List.copyOf(keys);
        Assert.notEmpty(keys, "finaxis.rails.signing.keys must list at least one key");
        Assert.isTrue(
                keys.stream().map(Key::id).distinct().count() == keys.size(),
                "finaxis.rails.signing.keys must name each key ID once");
    }

    Map<String, PublicKey> publicKeys() {
        var byId = new HashMap<String, PublicKey>();
        keys.forEach(key -> byId.put(key.id(), key.parsed()));
        return Map.copyOf(byId);
    }

    /**
     * @param id the {@code keyid} the rails sign with: 1 to 64 letters, digits, '.', '_' or '-'
     * @param publicKey the Ed25519 public key, X.509 SubjectPublicKeyInfo DER in base64
     */
    record Key(String id, String publicKey) {

        private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

        Key {
            Assert.isTrue(
                    ID.matcher(id).matches(),
                    "finaxis.rails.signing.keys[].id must be 1 to 64 letters, digits, '.', '_' or '-'");
            parse(publicKey);
        }

        PublicKey parsed() {
            return parse(publicKey);
        }

        private static PublicKey parse(String base64) {
            try {
                return KeyFactory.getInstance("Ed25519")
                        .generatePublic(
                                new X509EncodedKeySpec(Base64.getDecoder().decode(base64.strip())));
            } catch (IllegalArgumentException | GeneralSecurityException e) {
                throw new IllegalArgumentException(
                        "finaxis.rails.signing.keys[].public-key must be an Ed25519 public key, X.509 DER in base64");
            }
        }
    }
}

package dev.finaxis.core.rails.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

/**
 * Verifies the rails' signature on a request (billing design, section 7.1; rails simulator design,
 * section 6.3): the {@code Content-Digest} of the body (RFC 9530), then an Ed25519 signature under
 * HTTP Message Signatures (RFC 9421) over the method, the path, the content type and that digest,
 * by a key the core is configured with, then the time it was made.
 *
 * <p>Only the form the rails send is accepted: one signature labelled {@code rails}, covering
 * exactly those four components, with {@code created}, {@code keyid} and {@code alg} and nothing
 * else. The parameters are signed as they were sent, so nothing in them is normalized.
 */
final class SignatureVerifier {

    static final String CONTENT_DIGEST = "Content-Digest";
    static final String SIGNATURE_INPUT = "Signature-Input";
    static final String SIGNATURE = "Signature";

    static final Duration MAX_AGE = Duration.ofMinutes(5);
    static final Duration MAX_AHEAD = Duration.ofMinutes(1);

    private static final Pattern DIGEST = Pattern.compile("sha-256=:([A-Za-z0-9+/]{43}=):");

    private static final Pattern INPUT =
            Pattern.compile("rails=(\\(\"@method\" \"@path\" \"content-type\" \"content-digest\"\\)"
                    + ";created=([0-9]{1,12});keyid=\"([A-Za-z0-9._-]{1,64})\";alg=\"ed25519\")");

    private static final Pattern SIGNATURE_VALUE = Pattern.compile("rails=:([A-Za-z0-9+/]{86}==):");

    /** Why a request was refused: for the core's logs and metrics, never for the caller. */
    enum Refusal {
        MISSING_HEADER,
        DIGEST_MISMATCH,
        UNSUPPORTED_SIGNATURE,
        UNKNOWN_KEY,
        BAD_SIGNATURE,
        STALE,
        FROM_THE_FUTURE
    }

    /** What the verification found: the key that signed the request, or why it was refused. */
    sealed interface Verification {

        /** Signed by the key the core knows by {@code keyId}. */
        record Verified(String keyId) implements Verification {}

        record Refused(Refusal reason) implements Verification {}
    }

    private final Map<String, PublicKey> keys;
    private final Clock clock;

    SignatureVerifier(Map<String, PublicKey> keys, Clock clock) {
        this.keys = Map.copyOf(keys);
        this.clock = clock;
    }

    /** Verifies the request, whose body has already been read in full. */
    Verification verify(HttpServletRequest request, byte[] body) {
        @Nullable String digestHeader = request.getHeader(CONTENT_DIGEST);
        @Nullable String inputHeader = request.getHeader(SIGNATURE_INPUT);
        @Nullable String signatureHeader = request.getHeader(SIGNATURE);
        @Nullable String contentType = request.getHeader(HttpHeaders.CONTENT_TYPE);
        if (digestHeader == null || inputHeader == null || signatureHeader == null || contentType == null) {
            return new Verification.Refused(Refusal.MISSING_HEADER);
        }

        Matcher digest = DIGEST.matcher(digestHeader);
        if (!digest.matches() || !MessageDigest.isEqual(Base64.getDecoder().decode(digest.group(1)), sha256(body))) {
            return new Verification.Refused(Refusal.DIGEST_MISMATCH);
        }

        Matcher input = INPUT.matcher(inputHeader);
        Matcher signature = SIGNATURE_VALUE.matcher(signatureHeader);
        if (!input.matches() || !signature.matches()) {
            return new Verification.Refused(Refusal.UNSUPPORTED_SIGNATURE);
        }
        @Nullable PublicKey key = keys.get(input.group(3));
        if (key == null) {
            return new Verification.Refused(Refusal.UNKNOWN_KEY);
        }
        String base = signatureBase(
                List.of(
                        Map.entry("@method", request.getMethod()),
                        Map.entry("@path", request.getRequestURI()),
                        Map.entry("content-type", contentType.strip()),
                        Map.entry("content-digest", digestHeader)),
                input.group(1));
        if (!ed25519(key, base, Base64.getDecoder().decode(signature.group(1)))) {
            return new Verification.Refused(Refusal.BAD_SIGNATURE);
        }

        // Checked once the signature holds, so a forged time cannot be told apart from a forged signature.
        Instant created = Instant.ofEpochSecond(Long.parseLong(input.group(2)));
        Instant now = clock.instant();
        if (created.isBefore(now.minus(MAX_AGE))) {
            return new Verification.Refused(Refusal.STALE);
        }
        if (created.isAfter(now.plus(MAX_AHEAD))) {
            return new Verification.Refused(Refusal.FROM_THE_FUTURE);
        }
        return new Verification.Verified(input.group(3));
    }

    static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
    }

    /** The signature base of RFC 9421, section 2.5: one line per component, then the parameters, no final newline. */
    static String signatureBase(List<Map.Entry<String, String>> components, String parameters) {
        var base = new StringBuilder();
        components.forEach(component -> base.append('"')
                .append(component.getKey())
                .append("\": ")
                .append(component.getValue())
                .append('\n'));
        return base.append("\"@signature-params\": ").append(parameters).toString();
    }

    static boolean ed25519(PublicKey key, String base, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(base.getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(signature);
        } catch (InvalidKeyException | SignatureException e) {
            return false;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Every Java runtime from 15 has Ed25519", e);
        }
    }
}

package dev.axion.core.idempotency.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * A request whose body was read in full before the controller runs, so the body can be hashed and
 * then read again by the controller, byte for byte.
 */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    /**
     * Reads the body of the request, refusing one over {@code limit} bytes with {@code 413} before
     * reading it when its length is declared.
     */
    static CachedBodyRequest read(HttpServletRequest request, int limit) throws IOException {
        if (request.getContentLengthLong() > limit) {
            throw IdempotencyProblems.contentTooLarge(limit);
        }
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            throw IdempotencyProblems.contentTooLarge(limit);
        }
        return new CachedBodyRequest(request, body);
    }

    byte[] body() {
        return body.clone();
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public ServletInputStream getInputStream() {
        return new CachedInputStream(new ByteArrayInputStream(body));
    }

    @Override
    public BufferedReader getReader() {
        @Nullable String encoding = getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    private static final class CachedInputStream extends ServletInputStream {

        private final ByteArrayInputStream bytes;

        CachedInputStream(ByteArrayInputStream bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read() {
            return bytes.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return bytes.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
            return bytes.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("The body was read already; it is served synchronously");
        }
    }
}

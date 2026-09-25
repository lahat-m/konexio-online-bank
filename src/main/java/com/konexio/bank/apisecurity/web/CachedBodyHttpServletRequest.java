package com.konexio.bank.apisecurity.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.jspecify.annotations.NonNull;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Holds the request body in memory so it can be read twice: once to hash it,
 * once by the handler.
 *
 * <p>Spring's {@code ContentCachingRequestWrapper} is the usual answer, but it
 * caches what has already been read, and this filter needs the body
 * <em>before</em> the handler reads anything — so it reads the stream itself and
 * replays it.
 *
 * <p>Only wraps requests this filter already decided to protect, which are
 * small JSON bodies on money-moving endpoints. It is not something to put in
 * front of file uploads.
 */
class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffer = new ByteArrayInputStream(body);
        return new ServletInputStream() {

            @Override
            public boolean isFinished() {
                return buffer.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("This request is already fully buffered");
            }

            @Override
            public int read() {
                return buffer.read();
            }

            @Override
            public int read(byte @NonNull [] target, int offset, int length) {
                return buffer.read(target, offset, length);
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
    }

    private Charset charset() {
        String encoding = getCharacterEncoding();
        return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
    }
}

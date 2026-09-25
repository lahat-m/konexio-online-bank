package com.konexio.bank.shared.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Base of every failure this application reports to a client. Each subclass
 * fixes the HTTP status and the {@code type} slug of the RFC 9457
 * {@code ProblemDetail} it becomes, so {@link GlobalExceptionHandler} needs one
 * handler for all of them and a new failure mode cannot accidentally be
 * rendered as a 500.
 *
 * <p>Throwing sites supply only {@code detail} — the human-readable sentence
 * for this specific occurrence.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String problemType;
    private final String title;

    protected ApiException(HttpStatus status, String problemType, String title, String detail) {
        super(detail);
        this.status = status;
        this.problemType = problemType;
        this.title = title;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** Slug appended to {@link ProblemTypes#BASE_URI} to form the problem {@code type}. */
    public String getProblemType() {
        return problemType;
    }

    public String getTitle() {
        return title;
    }

    /**
     * Extra members to put on the problem document, beyond the five RFC 9457
     * defines.
     *
     * <p>Empty for most failures: a {@code detail} sentence is usually the whole
     * answer. It matters where the client has to <em>do</em> something with the
     * reason — retry after so many seconds, or render the three closure checks
     * that failed — and a sentence would mean parsing English to find out what.
     */
    public Map<String, Object> getProperties() {
        return Map.of();
    }
}

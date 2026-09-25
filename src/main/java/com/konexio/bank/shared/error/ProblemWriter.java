package com.konexio.bank.shared.error;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders an {@link ApiException} as an RFC 9457 problem document straight onto
 * the servlet response.
 *
 * <p>{@link GlobalExceptionHandler} covers everything thrown inside the
 * DispatcherServlet, which is almost everything — but a servlet filter runs
 * outside it, so a filter that rejects a request has no controller advice to
 * fall back on and would otherwise hand-write JSON. This keeps those responses
 * in the same shape, with the same {@code type} URIs, as the ones a controller
 * produces.
 */
@Component
public class ProblemWriter {

    private final ObjectMapper objectMapper;

    ProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, String instance, ApiException exception) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.getStatus(), exception.getMessage());
        problem.setType(ProblemTypes.of(exception.getProblemType()));
        problem.setTitle(exception.getTitle());
        problem.setInstance(URI.create(instance));

        exception.getProperties().forEach(problem::setProperty);
        if (exception instanceof RateLimitExceededException rateLimited) {
            response.setHeader(HttpHeaders.RETRY_AFTER,
                    String.valueOf(Math.max(1, rateLimited.getRetryAfter().toSeconds())));
        }

        response.setStatus(exception.getStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}

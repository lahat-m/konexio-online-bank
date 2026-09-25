package com.konexio.bank.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The single place HTTP error responses are produced, as RFC 9457
 * {@code ProblemDetail} documents (docs/rest-api.md §1).
 *
 * <p>Besides the application's own {@link ApiException} hierarchy it maps the
 * database's own rejections: several invariants in this system are enforced
 * only by PostgreSQL (overdraft and status-transition CHECKs, the "one open
 * MAIN account" unique index, the non-overlapping fee/limit EXCLUDE
 * constraints), and a constraint violation reaching this class is that
 * enforcement working, not an internal error — so it is translated to the
 * documented status rather than a 500.
 *
 * <p>It also handles the protocol-level failures Spring raises before a
 * controller is reached — wrong method, unreadable content type, nothing the
 * client will accept — because the catch-all below would otherwise render every
 * one of them as a 500.
 *
 * <p>Deliberately does not handle Spring Security's {@code AuthenticationException}:
 * it is raised inside the filter chain, where the resource server's entry point
 * already produces the right 401 before a controller advice would see it. An
 * {@code AccessDeniedException} usually is too — the access-denied handler
 * answers path rules, which is where this application puts them — but method
 * security raises it <em>after</em> dispatch, so it is mapped below rather than
 * left to the catch-all.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(ex.getStatus(), ex.getProblemType(), ex.getTitle(), ex.getMessage(), request);
        ex.getProperties().forEach(problem::setProperty);

        // Headers, unlike properties, cannot be expressed on the exception: only
        // this layer knows it is producing an HTTP response at all.
        HttpHeaders headers = new HttpHeaders();
        if (ex instanceof RateLimitExceededException rateLimited) {
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, rateLimited.getRetryAfter().toSeconds())));
        }
        return ResponseEntity.status(ex.getStatus()).headers(headers).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-error", "Validation failed",
                "One or more fields are invalid.", request);
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler({
        HandlerMethodValidationException.class,
        MissingServletRequestParameterException.class,
        MissingRequestHeaderException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    ProblemDetail handleMalformedRequest(Exception ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "validation-error", "Validation failed",
                shortMessage(ex), request);
    }

    /**
     * Content negotiation failed: the client asked for a representation this
     * endpoint cannot produce.
     *
     * <p>Answered with no body on purpose. The one thing known about this caller
     * is which media types they will accept, and a problem document is not among
     * them — writing one would fail in the message converter and turn a 406 into
     * a 500, which is how this was reaching clients before.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Void> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type", "Unsupported media type",
                "This endpoint does not accept %s.".formatted(ex.getContentType()), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "Method not allowed",
                "%s is not supported on this path.".formatted(ex.getMethod()), request);
        HttpHeaders headers = new HttpHeaders();
        if (ex.getSupportedHttpMethods() != null) {
            headers.setAllow(ex.getSupportedHttpMethods());
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(headers).body(problem);
    }

    /**
     * A denial from method security, which is thrown inside the handler and so
     * never reaches the filter chain's access-denied handler. Without this it
     * would fall to the catch-all below and answer 500 where the contract
     * promises 403.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "access-denied", "Access denied",
                "You do not have permission to perform this action.", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Not found", "No endpoint for this path.", request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLocking(OptimisticLockingFailureException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "concurrent-modification", "Concurrent modification",
                "The resource changed while this request was in flight. Retry with the current state.", request);
    }

    /**
     * SQLSTATE → HTTP, per README.md "Error mapping". A 42501 means the
     * application tried to write a table it is not granted (an append-only
     * ledger or audit row, or {@code account.ledger_balance}) — that is a bug in
     * this codebase, so it stays a 500 and is logged at error level.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        String sqlState = sqlStateOf(ex);
        ProblemDetail problem = switch (sqlState == null ? "" : sqlState) {
            case "23514" -> problem(HttpStatus.UNPROCESSABLE_ENTITY, "business-rule-violation",
                    "Business rule violation", "The database rejected this change: " + constraintOf(ex), request);
            case "23505" -> problem(HttpStatus.CONFLICT, "duplicate-resource", "Duplicate resource",
                    "A conflicting record already exists: " + constraintOf(ex), request);
            case "23P01" -> problem(HttpStatus.CONFLICT, "overlapping-period", "Overlapping period",
                    "This record overlaps an existing one: " + constraintOf(ex), request);
            case "23503" -> problem(HttpStatus.UNPROCESSABLE_ENTITY, "missing-reference", "Missing reference",
                    "A referenced record does not exist: " + constraintOf(ex), request);
            default -> {
                log.error("Unmapped data integrity violation (SQLSTATE {})", sqlState, ex);
                yield problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                        "The request could not be completed.", request);
            }
        };
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        String sqlState = sqlStateOf(ex);
        if ("42501".equals(sqlState)) {
            log.error("Write rejected by least-privilege grants — this is a bug, not a client error", ex);
        } else {
            log.error("Unhandled exception", ex);
        }
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "The request could not be completed.", request);
    }

    private static ProblemDetail problem(
            HttpStatus status, String type, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(ProblemTypes.of(type));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    private static String sqlStateOf(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }

    /**
     * The constraint name only — never the driver's full message, which repeats
     * the offending row values (phone numbers, national ID hashes, amounts) and
     * would put them in a client-visible response body.
     */
    private static String constraintOf(DataIntegrityViolationException ex) {
        return ex.getMostSpecificCause() instanceof SQLException sqlException
                        && sqlException.getMessage() != null
                ? firstConstraintName(sqlException.getMessage())
                : "constraint violated";
    }

    private static String firstConstraintName(String message) {
        int start = message.indexOf('"');
        int end = start < 0 ? -1 : message.indexOf('"', start + 1);
        return end > start ? message.substring(start + 1, end) : "constraint violated";
    }

    private static String shortMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "The request could not be read.";
        }
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }
}

package com.sellerdesk.common.error;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatusCode;

/**
 * The single JSON error format returned by every endpoint (see docs/ARCHITECTURE.md §5).
 *
 * @param timestamp   when the error happened (UTC)
 * @param status      HTTP status code, repeated in the body for convenience
 * @param code        stable machine-readable code, e.g. {@code ORDER_NOT_FOUND}; the frontend switches on this
 * @param message     human-readable text that is safe to show to the user
 * @param path        request path that failed
 * @param traceId     short id also written to the server log, to match a user report to a log line
 * @param fieldErrors validation problems per field; empty list when not a validation error
 */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String traceId,
        List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {
    }

    public static ApiError of(HttpStatusCode status, String code, String message, String path) {
        return of(status, code, message, path, List.of());
    }

    public static ApiError of(HttpStatusCode status, String code, String message, String path,
                              List<FieldError> fieldErrors) {
        return new ApiError(Instant.now(), status.value(), code, message, path, newTraceId(),
                List.copyOf(fieldErrors));
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

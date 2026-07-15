package com.multitoolagent.agent.exception;

import com.multitoolagent.agent.dto.ApiError;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions into consistent {@link ApiError} bodies with the right HTTP status, so
 * clients always get the same error shape. Nothing is swallowed: unexpected errors are logged in
 * full and returned as a generic 500 (no internal details leak to the caller).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({TaskNotFoundException.class, ConversationNotFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(RuntimeException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(AgentExecutionException.class)
    public ResponseEntity<ApiError> handleAgentExecution(AgentExecutionException e) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse("Validation failed");
        return build(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * A malformed client request that we validate in code rather than via annotations (e.g. an
     * out-of-range {@code GET /tasks} paging parameter, whose bounds are config-driven). Mapped to the
     * same 400 {@link ApiError} shape as body validation.
     */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(InvalidRequestException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * The request body isn't readable as the expected JSON (e.g. a missing quote, so {@code 20^2}
     * parses as a bare number). That's a client mistake, so it maps to 400 rather than falling through
     * to the catch-all 500. The parser's message is not echoed back &mdash; it can be noisy and leaks
     * internals; a stable hint is friendlier.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        log.debug("Malformed request body: {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Malformed request body: expected JSON like {\"task\": \"...\"}.");
    }

    @ExceptionHandler({ToolExecutionException.class, PersistenceException.class})
    public ResponseEntity<ApiError> handleServerSide(RuntimeException e) {
        log.error("Server-side failure", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e) {
        // Browsers auto-request paths we don't serve (e.g. /favicon.ico). That's an ordinary 404,
        // not a server error, so answer it quietly instead of logging a stack trace at ERROR.
        log.debug("No static resource: {}", e.getMessage());
        return build(HttpStatus.NOT_FOUND, "Resource not found");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message) {
        ApiError body = new ApiError(status.value(), status.getReasonPhrase(), message, Instant.now());
        return ResponseEntity.status(status).body(body);
    }
}

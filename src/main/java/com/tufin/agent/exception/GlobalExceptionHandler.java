package com.tufin.agent.exception;

import com.tufin.agent.dto.ApiError;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ApiError> handleTaskNotFound(TaskNotFoundException e) {
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
     * Constraint violations on request-parameter constraints (e.g. {@code @Min}/{@code @Max} on the
     * {@code GET /tasks} paging params, via {@code @Validated} on the controller). These are a client
     * error, mapped to the same 400 {@link ApiError} shape as body validation.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> leafNode(violation.getPropertyPath()) + ": " + violation.getMessage())
                .orElse("Invalid request parameters");
        return build(HttpStatus.BAD_REQUEST, message);
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

    /** The trailing node of a violation's property path is the offending parameter (e.g. "size"). */
    private static String leafNode(Path propertyPath) {
        String name = "parameter";
        for (Path.Node node : propertyPath) {
            name = node.getName();
        }
        return name;
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message) {
        ApiError body = new ApiError(status.value(), status.getReasonPhrase(), message, Instant.now());
        return ResponseEntity.status(status).body(body);
    }
}

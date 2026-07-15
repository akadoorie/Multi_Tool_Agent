package com.multitoolagent.agent.dto;

import java.time.Instant;

/**
 * Consistent error body returned for every handled failure.
 *
 * @param status    HTTP status code.
 * @param error     short reason phrase (e.g. {@code Not Found}).
 * @param message   human-readable detail.
 * @param timestamp when the error was produced.
 */
public record ApiError(int status, String error, String message, Instant timestamp) {
}

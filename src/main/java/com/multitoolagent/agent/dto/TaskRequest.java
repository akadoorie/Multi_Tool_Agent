package com.multitoolagent.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for submitting a task &mdash; identical for a new task ({@code POST /task}) and a
 * follow-up ({@code POST /conversations/{conversationId}/task}). The conversation, when continuing one,
 * is identified by the path, not the body.
 *
 * @param task the natural-language task to solve.
 */
public record TaskRequest(
        @NotBlank(message = "task must not be blank")
        @Schema(
                description = "The natural-language task to solve.",
                example = "What is 15% of 240, and what is the current weather in Tel Aviv?")
        String task) {
}

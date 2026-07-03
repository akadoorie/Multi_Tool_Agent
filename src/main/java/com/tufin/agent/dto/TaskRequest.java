package com.tufin.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /task}.
 *
 * @param task           the natural-language task to solve.
 * @param conversationId optional id of an existing conversation to continue. Omit (or leave blank)
 *                       to start a new conversation; the response returns the minted id. An unknown
 *                       id is rejected with 404.
 */
public record TaskRequest(
        @NotBlank(message = "task must not be blank")
        @Schema(
                description = "The natural-language task to solve.",
                example = "What is 15% of 240, and what is the current weather in Tel Aviv?")
        String task,

        @Schema(
                description = "Optional: id of an existing conversation to continue. Omit to start a "
                        + "new one.")
        String conversationId) {
}

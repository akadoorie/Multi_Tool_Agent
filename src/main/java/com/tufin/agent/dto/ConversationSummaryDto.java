package com.tufin.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * A "conversation card" for {@code GET /conversations}: the id, the conversation's opening task (a
 * subject line, <em>not</em> a summary), the turn count, and when it was last active. Deliberately
 * cheap &mdash; no generated text.
 *
 * @param conversationId the conversation id (pass to {@code GET /conversations/{id}} or a follow-up).
 * @param firstTask      the conversation's first task, truncated &mdash; a subject line, NOT a summary.
 * @param turnCount      number of turns in the conversation.
 * @param lastActivityAt timestamp of the most recent turn.
 */
public record ConversationSummaryDto(
        @Schema(description = "The conversation id; pass to GET /conversations/{id} or "
                + "POST /conversations/{id}/task.")
        String conversationId,
        @Schema(description = "The conversation's first task (its opening question), truncated — a "
                + "subject line for the thread, NOT a summary of the whole conversation.")
        String firstTask,
        @Schema(description = "Number of turns in the conversation.", example = "3")
        int turnCount,
        @Schema(description = "Timestamp of the most recent turn (the conversation's last activity).")
        Instant lastActivityAt) {
}

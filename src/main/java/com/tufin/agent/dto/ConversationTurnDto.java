package com.tufin.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * One turn in a conversation, as returned by {@code GET /conversations/{id}}. Deliberately lightweight:
 * the task, its final answer, and which tools it used &mdash; but not the full reasoning/tool trace,
 * which stays available per-turn via {@code GET /tasks/{taskId}}.
 *
 * @param conversationOrder 0-based position of this turn within the conversation.
 * @param taskId            the turn's task id (fetch its full trace via {@code GET /tasks/{taskId}}).
 * @param task              the natural-language task for this turn.
 * @param finalAnswer       the agent's answer for this turn.
 * @param toolsUsed         distinct tools this turn invoked (null for an {@code Unsupported} answer).
 * @param createdAt         when this turn completed.
 */
public record ConversationTurnDto(
        @Schema(description = "0-based position of this turn within the conversation.", example = "0")
        int conversationOrder,
        @Schema(description = "This turn's task id; fetch its full trace via GET /tasks/{taskId}.")
        String taskId,
        @Schema(description = "The natural-language task for this turn.")
        String task,
        @Schema(description = "The agent's answer for this turn.")
        String finalAnswer,
        @Schema(description = "Distinct tools this turn invoked (null for an Unsupported answer).")
        List<String> toolsUsed,
        @Schema(description = "When this turn completed.")
        Instant createdAt) {
}

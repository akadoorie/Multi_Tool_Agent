package com.multitoolagent.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * One turn in a conversation, as returned by {@code GET /conversations/{id}}. Lightweight: the task,
 * its final answer, the short explanation behind that answer, and which tools it used &mdash; but not
 * the full step-by-step tool trace, which stays available per-turn via {@code GET /tasks/{taskId}}.
 *
 * @param conversationOrder 0-based position of this turn within the conversation.
 * @param taskId            the turn's task id (fetch its full trace via {@code GET /tasks/{taskId}}).
 * @param task              the natural-language task for this turn.
 * @param finalAnswer       the agent's answer for this turn (the value alone).
 * @param reasoning         the short explanation/caveat behind the answer (e.g. a tool not returning the
 *                          exact value, or an {@code Unsupported} verdict's reason); null when there was
 *                          none, in which case it is omitted from the JSON.
 * @param toolsUsed         distinct tools this turn invoked (null for an {@code Unsupported} answer).
 * @param createdAt         when this turn completed.
 * @param engine            which LLM client ran this turn ({@code spring-ai} or {@code langchain4j});
 *                          turns of one conversation may use different engines.
 */
public record ConversationTurnDto(
        @Schema(description = "0-based position of this turn within the conversation.", example = "0")
        int conversationOrder,
        @Schema(description = "This turn's task id; fetch its full trace via GET /tasks/{taskId}.")
        String taskId,
        @Schema(description = "The natural-language task for this turn.")
        String task,
        @Schema(description = "The agent's answer for this turn (the value alone).")
        String finalAnswer,
        @Schema(description = "Short explanation/caveat behind the answer; omitted when there was none.")
        String reasoning,
        @Schema(description = "Distinct tools this turn invoked (null for an Unsupported answer).")
        List<String> toolsUsed,
        @Schema(description = "When this turn completed.")
        Instant createdAt,
        @Schema(description = "Which LLM client ran this turn: spring-ai or langchain4j.", example = "spring-ai")
        String engine) {
}

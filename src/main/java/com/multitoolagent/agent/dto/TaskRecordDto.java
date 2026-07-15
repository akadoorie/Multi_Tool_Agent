package com.multitoolagent.agent.dto;

import com.multitoolagent.agent.model.TaskRollup;
import com.multitoolagent.agent.model.TraceStep;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Response body for {@code POST /task} and {@code GET /tasks/{id}}.
 *
 * <p>The domain {@link TraceStep} / {@link TaskRollup} records are reused here as-is: they are plain
 * data with the exact shape a client wants, so wrapping them in parallel DTO copies would be
 * needless duplication. The DTO boundary that matters &mdash; not leaking internal-only fields such
 * as the stored {@code createdAt} &mdash; is handled by this being a distinct response type.
 *
 * @param taskId            server-generated task id (use it to fetch the task later).
 * @param task              the original natural-language task.
 * @param finalAnswer       the agent's synthesised final answer.
 * @param trace             ordered execution trace.
 * @param rollup            latency/token totals for the task.
 * @param conversationId    id of the conversation this task belongs to; pass it back on a follow-up
 *                          {@code POST /task} to continue the conversation.
 * @param conversationOrder 0-based position of this task within its conversation (0 = the first turn).
 */
public record TaskRecordDto(
        @Schema(description = "Server-generated task id.", example = "5f2c1e9a-1c2b-4a7d-9e10-3c4f5a6b7c8d")
        String taskId,
        @Schema(description = "The original natural-language task.", example = "What is 15% of 240?")
        String task,
        @Schema(description = "The agent's final answer, synthesised from tool results.")
        String finalAnswer,
        @Schema(description = "Ordered trace of every reasoning step and tool call.")
        List<TraceStep> trace,
        @Schema(description = "Per-task latency and token totals.")
        TaskRollup rollup,
        @Schema(description = "Conversation id; pass it back on a follow-up POST /task to continue.")
        String conversationId,
        @Schema(description = "0-based position of this task within its conversation.", example = "0")
        int conversationOrder) {
}

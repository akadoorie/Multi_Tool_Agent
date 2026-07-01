package com.tufin.agent.model;

import java.time.Instant;
import java.util.List;

/**
 * The persisted domain record for one completed task &mdash; the source-of-truth shape written to
 * {@code tasks.jsonl}. Kept deliberately separate from the API DTOs so the storage format and the
 * HTTP contract can evolve independently.
 *
 * @param taskId      server-generated unique id.
 * @param task        the original natural-language task.
 * @param finalAnswer the agent's synthesised final answer.
 * @param trace       ordered execution trace (reasoning, tool calls, latency, tokens).
 * @param rollup      per-task latency/token totals.
 * @param createdAt   completion timestamp.
 */
public record TaskRecord(
        String taskId,
        String task,
        String finalAnswer,
        List<TraceStep> trace,
        TaskRollup rollup,
        Instant createdAt) {
}

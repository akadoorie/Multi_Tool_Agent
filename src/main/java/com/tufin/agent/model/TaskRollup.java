package com.tufin.agent.model;

/**
 * Per-task observability totals, computed once per run.
 *
 * @param totalLatencyMs    whole-task wall-clock time in milliseconds (measured from loop entry).
 * @param totalInputTokens  sum of prompt tokens across all model calls.
 * @param totalOutputTokens sum of completion tokens across all model calls.
 * @param iterations        number of reason&rarr;act&rarr;observe cycles executed.
 */
public record TaskRollup(
        long totalLatencyMs,
        int totalInputTokens,
        int totalOutputTokens,
        int iterations) {
}

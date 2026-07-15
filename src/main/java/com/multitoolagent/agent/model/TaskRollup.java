package com.multitoolagent.agent.model;

/**
 * Per-task observability totals, computed once per run.
 *
 * @param totalLatencyMs                whole-task wall-clock time in milliseconds (from loop entry).
 * @param totalInputTokens              sum of prompt tokens across all model calls.
 * @param totalOutputTokens             sum of completion tokens across all model calls.
 * @param iterations                    number of reason&rarr;act&rarr;observe cycles executed.
 * @param totalCacheReadInputTokens     prompt-cache tokens READ across all model calls (billed ~0.1x);
 *                                      0 until caching engages (see {@code ChatOptionsFactory}).
 * @param totalCacheCreationInputTokens prompt-cache tokens WRITTEN across all model calls (~1.25x).
 */
public record TaskRollup(
        long totalLatencyMs,
        int totalInputTokens,
        int totalOutputTokens,
        int iterations,
        long totalCacheReadInputTokens,
        long totalCacheCreationInputTokens) {
}

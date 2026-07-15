package com.multitoolagent.agent.agent;

import com.multitoolagent.agent.model.TaskRollup;
import com.multitoolagent.agent.model.TraceStep;
import java.util.List;

/**
 * The outcome of one agent run: the final answer, the full trace, and the latency/token rollup.
 *
 * @param finalAnswer the synthesised final answer.
 * @param trace       ordered execution trace.
 * @param rollup      per-task totals.
 */
public record LoopResult(String finalAnswer, List<TraceStep> trace, TaskRollup rollup) {
}

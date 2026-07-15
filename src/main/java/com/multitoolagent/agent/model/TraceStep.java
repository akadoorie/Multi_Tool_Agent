package com.multitoolagent.agent.model;

import java.util.List;

/**
 * One ordered entry in a task's execution trace. Steps come in three shapes, distinguished by
 * {@code type}:
 *
 * <ul>
 *   <li>{@code model_call} &mdash; a Claude turn that requested one or more tools. Carries the
 *       assistant's reasoning text, the model-call latency, and the token usage for that turn.</li>
 *   <li>{@code tool} &mdash; a single tool execution. Carries the tool name, input, output, and the
 *       tool's own latency. Tokens are {@code null} here on purpose: the tokens belong to the
 *       {@code model_call} that requested the tool, so attaching them to each tool step would make
 *       two tools in one turn look like double-counted usage.</li>
 *   <li>{@code reasoning} &mdash; a model turn that produced explanatory text but requested no tool.
 *       Precedes a synthetic {@code final_answer} of {@code "Unsupported"}; carries that turn's
 *       reasoning text, latency, and token usage.</li>
 *   <li>{@code final_answer} &mdash; the terminating Claude turn with no tool calls. Carries the
 *       final text, latency, token usage, and {@code toolsUsed} &mdash; the distinct tools the task
 *       invoked, so the answer text itself can stay clean.</li>
 * </ul>
 *
 * @param index      1-based position of this step within the whole trace.
 * @param iteration  1-based reason&rarr;act&rarr;observe cycle this step belongs to.
 * @param type       {@code "model_call"}, {@code "tool"}, {@code "reasoning"}, or {@code "final_answer"}.
 * @param toolsUsed  distinct tools the task invoked, in first-use order; only set on a tool-grounded
 *                   {@code final_answer} (null on other steps and on an {@code Unsupported} answer).
 * @param thought    assistant reasoning text (null for a {@code tool} step).
 * @param tool       tool name (null unless {@code type == "tool"}).
 * @param toolInput  parsed tool input (null unless {@code type == "tool"}).
 * @param toolOutput structured tool result (null unless {@code type == "tool"}).
 * @param latencyMs  wall-clock latency of this step in milliseconds.
 * @param tokens     token usage for a model turn (null for a {@code tool} step).
 */
public record TraceStep(
        int index,
        int iteration,
        String type,
        List<String> toolsUsed,
        String thought,
        String tool,
        Object toolInput,
        Object toolOutput,
        long latencyMs,
        TokenUsage tokens) {

    /** Prompt/completion/total token counts for a single model call. */
    public record TokenUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {
    }

    /** A model turn that requested tools. */
    public static TraceStep modelCall(int index, int iteration, String thought, long latencyMs,
            TokenUsage tokens) {
        return new TraceStep(index, iteration, "model_call", null, thought, null, null, null, latencyMs, tokens);
    }

    /** A single tool execution (tokens intentionally omitted &mdash; see the class Javadoc). */
    public static TraceStep tool(int index, int iteration, String tool, Object input, Object output,
            long latencyMs) {
        return new TraceStep(index, iteration, "tool", null, null, tool, input, output, latencyMs, null);
    }

    /** A model turn that produced reasoning text but requested no tool (precedes an Unsupported verdict). */
    public static TraceStep reasoning(int index, int iteration, String thought, long latencyMs,
            TokenUsage tokens) {
        return new TraceStep(index, iteration, "reasoning", null, thought, null, null, null, latencyMs, tokens);
    }

    /**
     * The terminating model turn that produced the final answer. {@code toolsUsed} lists the distinct
     * tools the task invoked (null when none were &mdash; an {@code Unsupported} answer).
     */
    public static TraceStep finalAnswer(int index, int iteration, List<String> toolsUsed, String thought,
            long latencyMs, TokenUsage tokens) {
        return new TraceStep(index, iteration, "final_answer", toolsUsed, thought, null, null, null, latencyMs, tokens);
    }
}

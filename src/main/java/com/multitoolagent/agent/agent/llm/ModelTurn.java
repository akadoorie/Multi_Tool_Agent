package com.multitoolagent.agent.agent.llm;

import com.multitoolagent.agent.agent.llm.AgentMessage.ToolCall;
import com.multitoolagent.agent.model.TraceStep;
import java.util.List;

/**
 * What one model call returned, in a framework-neutral shape.
 *
 * @param text                the assistant's text, or null if the turn carried none.
 * @param toolCalls           the tools the model asked to run; empty (never null) when it gave a final answer.
 * @param usage               input/output/total tokens for this call (zeros when the response had none).
 * @param cacheReadTokens     prompt-cache tokens read on this call (0 when absent / not cached).
 * @param cacheCreationTokens prompt-cache tokens written on this call (0 when absent / not cached).
 */
public record ModelTurn(
        String text,
        List<ToolCall> toolCalls,
        TraceStep.TokenUsage usage,
        long cacheReadTokens,
        long cacheCreationTokens) {

    public ModelTurn {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? new TraceStep.TokenUsage(0, 0, 0) : usage;
    }
}

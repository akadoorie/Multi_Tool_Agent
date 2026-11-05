package com.multitoolagent.agent.agent.llm;

import java.util.List;

/**
 * One way of talking to the LLM (Large Language Model) &mdash; e.g. through Spring AI or through
 * LangChain4j. This is the only seam between the agent and an AI framework: {@code AgentLoop} owns the
 * reason&rarr;act&rarr;observe loop and depends only on this interface (Dependency Inversion), so a
 * framework adapter never decides <em>what</em> the agent does, only <em>how</em> one model call is made.
 *
 * <p>Implementations must never execute tools themselves: they only report the tool calls the model
 * asked for. Execution stays in {@code AgentLoop}, which is what lets it time and trace every step.
 *
 * <p>Adding another framework is one new {@code @Component} implementing this interface; the
 * {@link LlmClientRegistry} picks it up automatically (Open/Closed).
 */
public interface ILlmClient {

    /** Stable id a caller uses to choose this client in a request (e.g. {@code "spring-ai"}). */
    String id();

    /**
     * Sends the whole conversation so far to the model once and returns its reply.
     *
     * @param conversation system prompt first, then user/assistant/tool-result messages oldest&rarr;newest.
     * @return the model's text, the tool calls it requested (possibly none), and its token usage.
     */
    ModelTurn call(List<AgentMessage> conversation);
}

package com.multitoolagent.agent.agent.llm;

import java.util.List;

/**
 * One message in the agent's conversation with the model, in our own framework-neutral shape. The loop
 * builds a list of these; each {@link ILlmClient} translates them into its framework's message types.
 * Keeping our own shape is what lets the loop stay free of Spring AI / LangChain4j imports.
 */
public sealed interface AgentMessage {

    /** The system prompt (always the first message). */
    record System(String text) implements AgentMessage {
    }

    /** A user turn: the task, or a prior turn's task replayed as conversation context. */
    record User(String text) implements AgentMessage {
    }

    /**
     * An assistant turn: its text (may be empty) and the tools it asked to run (may be empty).
     * Replayed back to the model so it sees its own earlier requests.
     */
    record Assistant(String text, List<ToolCall> toolCalls) implements AgentMessage {

        /** A plain-text assistant turn with no tool calls (e.g. a prior turn's final answer). */
        public static Assistant text(String text) {
            return new Assistant(text, List.of());
        }
    }

    /** The real results of the tools the previous assistant turn asked for, fed back to the model. */
    record ToolResults(List<ToolResponse> responses) implements AgentMessage {
    }

    /**
     * A tool the model asked to run.
     *
     * @param id            the model's id for this call; the matching {@link ToolResponse} must echo it.
     * @param name          the tool name (e.g. {@code "calculator"}).
     * @param argumentsJson the arguments as a JSON object string.
     */
    record ToolCall(String id, String name, String argumentsJson) {
    }

    /**
     * The result of one tool call.
     *
     * @param id         the id of the {@link ToolCall} this answers.
     * @param name       the tool name.
     * @param resultJson the tool's result serialised as JSON.
     */
    record ToolResponse(String id, String name, String resultJson) {
    }
}

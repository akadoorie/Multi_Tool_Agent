package com.tufin.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A capability the agent can invoke. Each implementation is a Spring {@code @Component} that
 * self-registers into the {@link ToolRegistry}, so adding a new tool needs no change to the agent
 * loop, registry, or controller (Open/Closed Principle).
 *
 * <p>Implementations must be side-effect-safe on bad input: for <em>expected</em> failures (invalid
 * arguments, a city that doesn't exist, an upstream timeout) return {@link ToolResult#error} rather
 * than throwing, so the failure is fed back into the loop as data the agent can recover from.
 */
public interface Tool {

    /** Unique, stable tool id the model uses to call it (e.g. {@code "calculator"}). */
    String name();

    /** Natural-language description telling the model <em>when</em> to use this tool. */
    String description();

    /** JSON Schema (as a string) describing the tool's input object, advertised to the model. */
    String inputSchema();

    /**
     * Executes the tool against validated input.
     *
     * @param input the model-supplied arguments, already parsed to a JSON tree.
     * @return a structured success or error result.
     */
    ToolResult execute(JsonNode input);
}

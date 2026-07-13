package com.tufin.agent.agent;

import com.tufin.agent.tool.ITool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Adapts our {@link ITool} to a Spring AI {@link ToolCallback} for the sole purpose of advertising the
 * tool's name, description, and input schema to Claude.
 *
 * <p>Because the agent loop runs with internal tool execution disabled, Spring AI never invokes
 * {@link #call}. We make that invariant explicit and self-documenting by throwing if it is ever
 * called: execution belongs to {@link AgentLoop}, which is what lets us build the trace.
 */
public class SpringAiToolAdapter implements ToolCallback {

    private final ITool tool;

    public SpringAiToolAdapter(ITool tool) {
        this.tool = tool;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .build();
    }

    @Override
    public String call(String toolInput) {
        throw new UnsupportedOperationException(
                "Tool '" + tool.name() + "' execution is owned by AgentLoop; "
                        + "internal tool execution is disabled.");
    }
}

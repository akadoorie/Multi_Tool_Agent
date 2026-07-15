package com.multitoolagent.agent.tool;

/**
 * Structured outcome of a tool execution. Both success and failure are ordinary return values (not
 * exceptions) so the agent loop can feed either back to the model uniformly.
 *
 * @param ok    whether the tool succeeded.
 * @param data  the result payload on success (null on failure).
 * @param error a human-readable error message on failure (null on success).
 */
public record ToolResult(boolean ok, Object data, String error) {

    public static ToolResult ok(Object data) {
        return new ToolResult(true, data, null);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, null, message);
    }
}

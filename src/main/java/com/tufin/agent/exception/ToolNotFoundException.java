package com.tufin.agent.exception;

/** Thrown when the model requests a tool name that is not registered. */
public class ToolNotFoundException extends RuntimeException {

    public ToolNotFoundException(String toolName) {
        super("No tool registered with name: " + toolName);
    }
}

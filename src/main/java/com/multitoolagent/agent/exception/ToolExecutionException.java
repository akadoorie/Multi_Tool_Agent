package com.multitoolagent.agent.exception;

/** Thrown for an unexpected, non-recoverable failure while executing a tool. */
public class ToolExecutionException extends RuntimeException {

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}

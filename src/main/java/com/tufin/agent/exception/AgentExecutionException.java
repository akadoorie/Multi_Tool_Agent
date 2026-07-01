package com.tufin.agent.exception;

/**
 * Thrown when the agent cannot produce a final answer &mdash; most importantly when the
 * max-iterations cap is hit. We fail loudly here rather than returning a fabricated answer.
 */
public class AgentExecutionException extends RuntimeException {

    public AgentExecutionException(String message) {
        super(message);
    }
}

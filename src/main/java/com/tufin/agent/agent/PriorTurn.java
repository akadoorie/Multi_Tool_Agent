package com.tufin.agent.agent;

/**
 * A prior conversation turn as context for a follow-up: just the user's task and the agent's final
 * answer (no trace / tool steps &mdash; answers only, to keep the resent context cheap).
 *
 * @param task        the user's task on that turn.
 * @param finalAnswer the agent's answer on that turn.
 */
public record PriorTurn(String task, String finalAnswer) {
}

package com.multitoolagent.agent.model;

import java.time.Instant;

/**
 * A lightweight summary of one conversation for the recent-conversations listing &mdash; its id, its
 * opening task, how many turns it has, and when it was last active. The repository builds it from its
 * indexes without loading full records.
 *
 * @param conversationId the conversation's id.
 * @param firstTask      the conversation's opening task text (untruncated; the DTO layer truncates it).
 * @param turnCount      number of turns in the conversation.
 * @param lastActivityAt timestamp of the most recent turn.
 */
public record ConversationSummary(String conversationId, String firstTask, int turnCount,
        Instant lastActivityAt) {
}

package com.multitoolagent.agent.exception;

/** Thrown when a conversation id is provided that does not exist in the store. Maps to HTTP 404. */
public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(String conversationId) {
        super("No conversation found with id: " + conversationId);
    }
}

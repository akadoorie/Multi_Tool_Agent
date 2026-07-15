package com.multitoolagent.agent.exception;

/** Thrown for a malformed client request, e.g. an out-of-range paging parameter. Maps to HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}

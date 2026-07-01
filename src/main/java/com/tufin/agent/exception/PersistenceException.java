package com.tufin.agent.exception;

/** Thrown when reading from or writing to the task store fails. */
public class PersistenceException extends RuntimeException {

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}

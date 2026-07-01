package com.tufin.agent.exception;

/** Thrown when a task id is requested that does not exist in the store. Maps to HTTP 404. */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String taskId) {
        super("No task found with id: " + taskId);
    }
}

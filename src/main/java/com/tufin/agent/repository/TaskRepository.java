package com.tufin.agent.repository;

import com.tufin.agent.model.TaskRecord;
import java.util.List;
import java.util.Optional;

/**
 * Persistence abstraction for tasks. Depending on this interface (rather than a concrete file store)
 * is the seam that lets a database implementation be dropped in later without touching the service.
 */
public interface TaskRepository {

    /** Persists a completed task. */
    void save(TaskRecord record);

    /** Looks up a task by id. */
    Optional<TaskRecord> findById(String taskId);

    /** Returns all stored tasks. */
    List<TaskRecord> findAll();
}

package com.tufin.agent.service;

import com.tufin.agent.agent.AgentLoop;
import com.tufin.agent.agent.LoopResult;
import com.tufin.agent.dto.DtoMapper;
import com.tufin.agent.dto.TaskResponse;
import com.tufin.agent.exception.TaskNotFoundException;
import com.tufin.agent.model.TaskRecord;
import com.tufin.agent.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a task end to end: run the agent loop, assemble the {@link TaskRecord}, persist it,
 * and map it to the API response. Also serves stored tasks back for the {@code GET} endpoints.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String MDC_TASK_ID = "task_id";

    private final AgentLoop loop;
    private final TaskRepository repository;
    private final Clock clock;

    public AgentService(AgentLoop loop, TaskRepository repository, Clock clock) {
        this.loop = loop;
        this.repository = repository;
        this.clock = clock;
    }

    /** Runs the agent for a new task, persists the result, and returns it. */
    public TaskResponse solve(String task) {
        String taskId = UUID.randomUUID().toString();
        MDC.put(MDC_TASK_ID, taskId);
        try {
            log.info("Starting task: {}", task);
            LoopResult result = loop.run(task);
            TaskRecord record = new TaskRecord(taskId, task, result.finalAnswer(), result.trace(),
                    result.rollup(), Instant.now(clock));
            repository.save(record);
            log.info("Stored task {} ({} input / {} output tokens, {} ms)", taskId,
                    result.rollup().totalInputTokens(), result.rollup().totalOutputTokens(),
                    result.rollup().totalLatencyMs());
            return DtoMapper.toResponse(record);
        } finally {
            MDC.remove(MDC_TASK_ID);
        }
    }

    /** Returns a stored task, or throws {@link TaskNotFoundException} (mapped to 404). */
    public TaskResponse get(String taskId) {
        return repository.findById(taskId)
                .map(DtoMapper::toResponse)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    /** Lists all stored tasks (showcases the queryable store). */
    public List<TaskResponse> list() {
        return repository.findAll().stream().map(DtoMapper::toResponse).toList();
    }
}

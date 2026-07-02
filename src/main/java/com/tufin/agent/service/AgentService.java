package com.tufin.agent.service;

import com.tufin.agent.agent.AgentLoop;
import com.tufin.agent.agent.LoopResult;
import com.tufin.agent.dto.DtoMapper;
import com.tufin.agent.dto.PagedTasksResponse;
import com.tufin.agent.dto.TaskRecordDto;
import com.tufin.agent.exception.TaskNotFoundException;
import com.tufin.agent.model.TaskRecord;
import com.tufin.agent.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
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
    public TaskRecordDto solve(String task) {
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
            return DtoMapper.toDto(record);
        } finally {
            MDC.remove(MDC_TASK_ID);
        }
    }

    /** Returns a stored task, or throws {@link TaskNotFoundException} (mapped to 404). */
    public TaskRecordDto get(String taskId) {
        return repository.findById(taskId)
                .map(DtoMapper::toDto)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    /**
     * Returns one page of stored tasks, newest first. The whole set is sorted by {@code createdAt}
     * descending (tie-broken by {@code taskId} ascending) <em>before</em> slicing, because the
     * repository's in-memory index has no defined iteration order &mdash; paginating over that
     * directly would let pages overlap or drop records. The dataset is small and fully in-memory, so
     * sorting here is cheap and keeps the {@link TaskRepository} interface minimal.
     *
     * @param page 0-based page index (validated {@code >= 0} at the controller).
     * @param size page size (validated {@code 1..100} at the controller).
     */
    public PagedTasksResponse list(int page, int size) {
        List<TaskRecord> ordered = repository.findAll().stream()
                .sorted(Comparator.comparing(TaskRecord::createdAt).reversed()
                        .thenComparing(TaskRecord::taskId))
                .toList();

        int totalElements = ordered.size();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        // Long math so a huge page index can't overflow before we clamp it to the list bounds.
        int from = (int) Math.min((long) page * size, totalElements);
        int to = (int) Math.min((long) from + size, totalElements);

        List<TaskRecordDto> items = ordered.subList(from, to).stream()
                .map(DtoMapper::toDto)
                .toList();
        return new PagedTasksResponse(items, page, size, totalElements, totalPages);
    }
}

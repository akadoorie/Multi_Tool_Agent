package com.tufin.agent.service;

import com.tufin.agent.agent.AgentLoop;
import com.tufin.agent.agent.LoopResult;
import com.tufin.agent.agent.PriorTurn;
import com.tufin.agent.config.AppProperties;
import com.tufin.agent.dto.ConversationTurnDto;
import com.tufin.agent.dto.DtoMapper;
import com.tufin.agent.dto.PagedConversationResponse;
import com.tufin.agent.dto.PagedTasksResponse;
import com.tufin.agent.dto.TaskRecordDto;
import com.tufin.agent.exception.ConversationNotFoundException;
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
 * Orchestrates a task end to end: resolve its conversation, run the agent loop (seeded with capped
 * prior context on a follow-up), persist the {@link TaskRecord}, and map it to the API response. Also
 * serves stored tasks and conversations back for the {@code GET} endpoints.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String MDC_TASK_ID = "task_id";

    private final AgentLoop loop;
    private final TaskRepository repository;
    private final Clock clock;
    private final AppProperties props;

    public AgentService(AgentLoop loop, TaskRepository repository, Clock clock, AppProperties props) {
        this.loop = loop;
        this.repository = repository;
        this.clock = clock;
        this.props = props;
    }

    /** Runs a standalone task (a fresh one-turn conversation). */
    public TaskRecordDto solve(String task) {
        return solve(task, null);
    }

    /**
     * Runs a task, optionally continuing a conversation, persists the result, and returns it (with the
     * conversation id/order so the client can continue).
     *
     * @param conversationId blank/null to start a new conversation; an existing id to continue it; an
     *                       unknown id triggers {@link ConversationNotFoundException} (404).
     */
    public TaskRecordDto solve(String task, String conversationId) {
        Conversation conversation = resolveConversation(conversationId);
        String taskId = UUID.randomUUID().toString();
        MDC.put(MDC_TASK_ID, taskId);
        try {
            log.info("Starting task in conversation {}: {}", conversation.id(), task);
            LoopResult result = loop.run(task, conversation.history());
            // conversationOrder is a placeholder here — the repository assigns the real one under its
            // write lock (atomically with the append) and returns the stored record.
            TaskRecord record = new TaskRecord(taskId, task, result.finalAnswer(), result.trace(),
                    result.rollup(), Instant.now(clock), conversation.id(), 0);
            TaskRecord stored = repository.save(record);
            log.info("Stored task {} (conversation {} turn {}; {} input / {} output tokens, {} ms)",
                    taskId, stored.conversationId(), stored.conversationOrder(),
                    result.rollup().totalInputTokens(), result.rollup().totalOutputTokens(),
                    result.rollup().totalLatencyMs());
            return DtoMapper.toDto(stored);
        } finally {
            MDC.remove(MDC_TASK_ID);
        }
    }

    /**
     * Resolves the conversation for a task: a blank id mints a fresh conversation (no context); a known
     * id loads only the last {@code app.conversation.context-turns} turns as context; an unknown id is
     * a 404.
     */
    private Conversation resolveConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return new Conversation(UUID.randomUUID().toString(), List.of());
        }
        if (repository.countByConversation(conversationId) == 0) {
            throw new ConversationNotFoundException(conversationId);
        }
        int contextTurns = props.conversation().contextTurns();
        List<PriorTurn> history = repository.findByConversation(conversationId, contextTurns).stream()
                .map(record -> new PriorTurn(record.task(), record.finalAnswer()))
                .toList();
        return new Conversation(conversationId, history);
    }

    /** A resolved conversation: its id and the capped prior turns (oldest first) to feed as context. */
    private record Conversation(String id, List<PriorTurn> history) {
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

    /**
     * Returns one page of a conversation's turns, oldest first, as lightweight {@link ConversationTurnDto}
     * views. Reads only the requested page from the repository (never the whole conversation).
     *
     * @param page 0-based page index (validated at the controller).
     * @param size page size (validated at the controller).
     * @throws ConversationNotFoundException if the conversation id is unknown (404).
     */
    public PagedConversationResponse getConversation(String conversationId, int page, int size) {
        int total = repository.countByConversation(conversationId);
        if (total == 0) {
            throw new ConversationNotFoundException(conversationId);
        }
        List<ConversationTurnDto> items = repository.findConversationPage(conversationId, page, size)
                .stream()
                .map(DtoMapper::toConversationTurn)
                .toList();
        int totalPages = (int) Math.ceil((double) total / size);
        return new PagedConversationResponse(items, page, size, total, totalPages);
    }
}

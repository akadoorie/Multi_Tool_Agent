package com.tufin.agent.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tufin.agent.config.AppProperties;
import com.tufin.agent.exception.PersistenceException;
import com.tufin.agent.model.ConversationSummary;
import com.tufin.agent.model.TaskRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * File-backed {@link TaskRepository}. The append-only JSON Lines file (one task per line) is the
 * durable source of truth; an in-memory index (task id &rarr; record) is built from it at startup and
 * updated on every append, giving O(1) lookups without a database.
 *
 * <p>A second, lightweight index maps {@code conversation_id} &rarr; the ordered list of that
 * conversation's task <em>ids</em> (ids only, so it stays small even for long conversations). It lets
 * conversation reads materialise just the turns asked for &mdash; never the whole store. Both indexes
 * are built at startup and updated on append.
 *
 * <p>Writes go to the file first and the indexes second, so an index can never hold a record that
 * isn't durably persisted. Appends are serialised on a single lock; the indexes are concurrent for
 * lock-free reads.
 */
@Repository
public class FileTaskRepository implements TaskRepository {

    private static final Logger log = LoggerFactory.getLogger(FileTaskRepository.class);

    private final Path file;
    private final ObjectMapper mapper;
    private final Map<String, TaskRecord> index = new ConcurrentHashMap<>();
    private final Map<String, List<String>> conversationIndex = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public FileTaskRepository(AppProperties props, ObjectMapper mapper) {
        this.file = Path.of(props.persistence().dataFile());
        this.mapper = mapper;
        initialiseStore();
        loadIndex();
    }

    private void initialiseStore() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.notExists(file)) {
                Files.createFile(file);
            }
        } catch (IOException e) {
            throw new PersistenceException("Failed to initialise task store at " + file, e);
        }
    }

    private void loadIndex() {
        try (Stream<String> lines = Files.lines(file)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> {
                try {
                    TaskRecord record = mapper.readValue(line, TaskRecord.class);
                    index.put(record.taskId(), record);
                    // File append order preserves each conversation's turn order (turns are appended
                    // 0,1,2,...). Legacy records with no conversation id aren't part of any conversation.
                    indexConversation(record);
                } catch (JsonProcessingException e) {
                    // One corrupt line must not brick startup; skip it and keep going.
                    log.warn("Skipping malformed line in {}", file, e);
                }
            });
            log.info("Loaded {} task(s) in {} conversation(s) from {}",
                    index.size(), conversationIndex.size(), file);
        } catch (IOException e) {
            throw new PersistenceException("Failed to read task store at " + file, e);
        }
    }

    /** Appends the record's id to its conversation's id-list (no-op for a record with no conversation). */
    private void indexConversation(TaskRecord record) {
        String conversationId = record.conversationId();
        if (conversationId != null && !conversationId.isBlank()) {
            conversationIndex.computeIfAbsent(conversationId, k -> new CopyOnWriteArrayList<>())
                    .add(record.taskId());
        }
    }

    @Override
    public TaskRecord save(TaskRecord record) {
        synchronized (writeLock) {
            // Assign conversation_order = the current turn count, atomically with the append, so two
            // turns in one conversation can never get the same order.
            int order = countByConversation(record.conversationId());
            TaskRecord stored = record.withConversationOrder(order);
            String json;
            try {
                json = mapper.writeValueAsString(stored);
            } catch (JsonProcessingException e) {
                throw new PersistenceException("Failed to serialise task " + stored.taskId(), e);
            }
            try {
                Files.writeString(file, json + System.lineSeparator(), StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new PersistenceException("Failed to append task " + stored.taskId(), e);
            }
            index.put(stored.taskId(), stored);   // only after the durable write succeeds
            indexConversation(stored);
            return stored;
        }
    }

    @Override
    public Optional<TaskRecord> findById(String taskId) {
        return Optional.ofNullable(index.get(taskId));
    }

    @Override
    public List<TaskRecord> findAll() {
        return new ArrayList<>(index.values());
    }

    @Override
    public int countByConversation(String conversationId) {
        return conversationIds(conversationId).size();
    }

    @Override
    public List<TaskRecord> findByConversation(String conversationId, int limit) {
        List<String> ids = conversationIds(conversationId);
        int from = Math.max(0, ids.size() - limit);
        return materialize(ids.subList(from, ids.size()));
    }

    @Override
    public List<TaskRecord> findConversationPage(String conversationId, int page, int size) {
        List<String> ids = conversationIds(conversationId);
        // Long math so a huge page index can't overflow before we clamp it to the list bounds.
        int from = (int) Math.min((long) page * size, ids.size());
        int to = (int) Math.min((long) from + size, ids.size());
        return materialize(ids.subList(from, to));
    }

    @Override
    public List<ConversationSummary> findConversationsPage(int page, int size) {
        // To rank by recency the file store must touch every conversation (O(#conversations)) — the same
        // honest ceiling as findAll(). A DB would ORDER BY last_activity_at DESC LIMIT ? OFFSET ?.
        List<ConversationSummary> ordered = conversationIndex.entrySet().stream()
                .map(entry -> summarize(entry.getKey(), new ArrayList<>(entry.getValue())))
                .filter(summary -> summary != null)
                .sorted(Comparator.comparing(ConversationSummary::lastActivityAt).reversed()
                        .thenComparing(ConversationSummary::conversationId))
                .toList();

        int total = ordered.size();
        // Long math so a huge page index can't overflow before we clamp to the list bounds.
        int from = (int) Math.min((long) page * size, total);
        int to = (int) Math.min((long) from + size, total);
        return ordered.subList(from, to);
    }

    @Override
    public int countConversations() {
        return conversationIndex.size();
    }

    /** Builds a summary from a conversation's ordered id-list: first id → opening task, last id → recency. */
    private ConversationSummary summarize(String conversationId, List<String> ids) {
        if (ids.isEmpty()) {
            return null;
        }
        TaskRecord first = index.get(ids.get(0));
        TaskRecord last = index.get(ids.get(ids.size() - 1));
        if (first == null || last == null) {
            return null;
        }
        return new ConversationSummary(conversationId, first.task(), ids.size(), last.createdAt());
    }

    /** A stable snapshot of a conversation's ordered ids (ascending by conversation_order). */
    private List<String> conversationIds(String conversationId) {
        return new ArrayList<>(conversationIndex.getOrDefault(conversationId, List.of()));
    }

    /** Resolves ids to records, dropping any that are somehow missing from the primary index. */
    private List<TaskRecord> materialize(List<String> ids) {
        return ids.stream().map(index::get).filter(record -> record != null).toList();
    }
}

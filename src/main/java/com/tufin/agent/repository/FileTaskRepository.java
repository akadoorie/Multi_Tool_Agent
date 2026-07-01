package com.tufin.agent.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tufin.agent.config.AppProperties;
import com.tufin.agent.exception.PersistenceException;
import com.tufin.agent.model.TaskRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * File-backed {@link TaskRepository}. The append-only JSON Lines file (one task per line) is the
 * durable source of truth; an in-memory index (task id &rarr; record) is built from it at startup and
 * updated on every append, giving O(1) lookups without a database.
 *
 * <p>Writes go to the file first and the index second, so the index can never hold a record that
 * isn't durably persisted. Appends are serialised on a single lock; the index is a
 * {@link ConcurrentHashMap} for lock-free reads.
 */
@Repository
public class FileTaskRepository implements TaskRepository {

    private static final Logger log = LoggerFactory.getLogger(FileTaskRepository.class);

    private final Path file;
    private final ObjectMapper mapper;
    private final Map<String, TaskRecord> index = new ConcurrentHashMap<>();
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
                } catch (JsonProcessingException e) {
                    // One corrupt line must not brick startup; skip it and keep going.
                    log.warn("Skipping malformed line in {}", file, e);
                }
            });
            log.info("Loaded {} task(s) from {}", index.size(), file);
        } catch (IOException e) {
            throw new PersistenceException("Failed to read task store at " + file, e);
        }
    }

    @Override
    public void save(TaskRecord record) {
        String json;
        try {
            json = mapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new PersistenceException("Failed to serialise task " + record.taskId(), e);
        }
        synchronized (writeLock) {
            try {
                Files.writeString(file, json + System.lineSeparator(), StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new PersistenceException("Failed to append task " + record.taskId(), e);
            }
            index.put(record.taskId(), record); // only after the durable write succeeds
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
}

package com.tufin.agent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tufin.agent.config.AppProperties;
import com.tufin.agent.model.TaskRecord;
import com.tufin.agent.model.TaskRollup;
import com.tufin.agent.model.TraceStep;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTaskRepositoryTest {

    @TempDir
    Path tempDir;

    /** Mirrors the production ObjectMapper config (snake_case + java.time as ISO strings). */
    private ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    private AppProperties propsFor(Path file) {
        return new AppProperties(
                new AppProperties.Agent("claude-sonnet-4-6", 8, 8192, 0.0, true),
                null, null,
                new AppProperties.Persistence(file.toString()));
    }

    private TaskRecord record(String id) {
        TraceStep step = TraceStep.finalAnswer(1, 1, "done", 5, new TraceStep.TokenUsage(1, 2, 3));
        return new TaskRecord(id, "task " + id, "answer " + id, List.of(step),
                new TaskRollup(10, 1, 2, 1), Instant.parse("2026-07-01T10:15:30Z"));
    }

    @Test
    void savesAndReadsBackById() {
        Path file = tempDir.resolve("tasks.jsonl");
        FileTaskRepository repo = new FileTaskRepository(propsFor(file), mapper());

        repo.save(record("a"));
        repo.save(record("b"));

        assertThat(repo.findById("a")).contains(record("a"));
        assertThat(repo.findAll()).hasSize(2);
        assertThat(repo.findById("missing")).isEmpty();
    }

    @Test
    void rebuildsIndexFromFileOnRestart() {
        Path file = tempDir.resolve("tasks.jsonl");
        new FileTaskRepository(propsFor(file), mapper()).save(record("a"));

        // A fresh repository instance (simulating a restart) must load the persisted task.
        FileTaskRepository reloaded = new FileTaskRepository(propsFor(file), mapper());

        assertThat(reloaded.findById("a")).contains(record("a"));
        assertThat(reloaded.findAll()).hasSize(1);
    }
}

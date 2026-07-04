package com.tufin.agent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tufin.agent.config.AppProperties;
import com.tufin.agent.model.ConversationSummary;
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
                new AppProperties.Persistence(file.toString()),
                null, null);
    }

    /** A record in its own single-turn conversation (so its assigned order is 0). */
    private TaskRecord record(String id) {
        return turn(id, "conv-" + id);
    }

    /** A record belonging to {@code conversationId} (its order is assigned by {@code save}). */
    private TaskRecord turn(String id, String conversationId) {
        TraceStep step = TraceStep.finalAnswer(1, 1, List.of("calculator"), "done", 5,
                new TraceStep.TokenUsage(1, 2, 3));
        return new TaskRecord(id, "task " + id, "answer " + id, List.of(step),
                new TaskRollup(10, 1, 2, 1, 0, 0), Instant.parse("2026-07-01T10:15:30Z"), conversationId, 0);
    }

    /** As {@link #turn} but with an explicit opening-task text and created-at (for recency tests). */
    private TaskRecord turnAt(String id, String conversationId, String task, String createdAt) {
        TraceStep step = TraceStep.finalAnswer(1, 1, List.of("calculator"), "done", 5,
                new TraceStep.TokenUsage(1, 2, 3));
        return new TaskRecord(id, task, "answer " + id, List.of(step),
                new TaskRollup(10, 1, 2, 1, 0, 0), Instant.parse(createdAt), conversationId, 0);
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

    @Test
    void assignsConversationOrderSequentiallyPerConversation() {
        FileTaskRepository repo = new FileTaskRepository(propsFor(tempDir.resolve("t.jsonl")), mapper());

        assertThat(repo.save(turn("a", "c1")).conversationOrder()).isZero();
        assertThat(repo.save(turn("b", "c1")).conversationOrder()).isEqualTo(1);
        assertThat(repo.save(turn("c", "c2")).conversationOrder()).isZero();   // separate conversation

        assertThat(repo.countByConversation("c1")).isEqualTo(2);
        assertThat(repo.countByConversation("unknown")).isZero();
    }

    @Test
    void findByConversationReturnsMostRecentLimitOldestFirst() {
        FileTaskRepository repo = new FileTaskRepository(propsFor(tempDir.resolve("t.jsonl")), mapper());
        repo.save(turn("a", "c1"));   // order 0
        repo.save(turn("b", "c1"));   // order 1
        repo.save(turn("c", "c1"));   // order 2

        List<TaskRecord> lastTwo = repo.findByConversation("c1", 2);

        assertThat(lastTwo).extracting(TaskRecord::taskId).containsExactly("b", "c");
        assertThat(lastTwo).extracting(TaskRecord::conversationOrder).containsExactly(1, 2);
    }

    @Test
    void conversationIndexRebuildsFromFileOnRestart() {
        Path file = tempDir.resolve("t.jsonl");
        FileTaskRepository repo = new FileTaskRepository(propsFor(file), mapper());
        repo.save(turn("a", "c1"));
        repo.save(turn("b", "c1"));

        FileTaskRepository reloaded = new FileTaskRepository(propsFor(file), mapper());

        assertThat(reloaded.countByConversation("c1")).isEqualTo(2);
        assertThat(reloaded.findByConversation("c1", 10)).extracting(TaskRecord::taskId)
                .containsExactly("a", "b");
        assertThat(reloaded.findConversationPage("c1", 0, 1)).extracting(TaskRecord::taskId)
                .containsExactly("a");
    }

    @Test
    void findConversationsPageRanksByLastActivityNewestFirstAndSlices() {
        FileTaskRepository repo = new FileTaskRepository(propsFor(tempDir.resolve("t.jsonl")), mapper());
        repo.save(turnAt("a0", "A", "opening task in conversation A", "2026-07-01T10:00:00Z"));
        repo.save(turnAt("a1", "A", "follow-up in A", "2026-07-01T12:00:00Z"));    // A last active 12:00
        repo.save(turnAt("b0", "B", "opening task in B", "2026-07-01T11:00:00Z"));  // B last active 11:00
        repo.save(turnAt("c0", "C", "opening task in C", "2026-07-01T09:00:00Z"));  // C last active 09:00

        assertThat(repo.countConversations()).isEqualTo(3);

        List<ConversationSummary> page0 = repo.findConversationsPage(0, 2);
        assertThat(page0).extracting(ConversationSummary::conversationId).containsExactly("A", "B");
        ConversationSummary a = page0.get(0);
        assertThat(a.turnCount()).isEqualTo(2);
        assertThat(a.firstTask()).isEqualTo("opening task in conversation A");   // opening task, untruncated
        assertThat(a.lastActivityAt()).isEqualTo(Instant.parse("2026-07-01T12:00:00Z"));

        // Second page returns the remaining (oldest) conversation.
        assertThat(repo.findConversationsPage(1, 2)).extracting(ConversationSummary::conversationId)
                .containsExactly("C");
    }
}

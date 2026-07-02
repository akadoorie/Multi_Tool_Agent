package com.tufin.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tufin.agent.agent.AgentLoop;
import com.tufin.agent.dto.PagedTasksResponse;
import com.tufin.agent.dto.TaskRecordDto;
import com.tufin.agent.model.TaskRecord;
import com.tufin.agent.model.TaskRollup;
import com.tufin.agent.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests the paging/ordering logic of {@link AgentService#list(int, int)} against a mocked repository.
 * The repository is stubbed to return records in a shuffled order (its real index has no defined
 * iteration order), so these assert the service imposes a deterministic newest-first ordering itself.
 */
class AgentServiceTest {

    private final AgentLoop loop = mock(AgentLoop.class);
    private final TaskRepository repository = mock(TaskRepository.class);
    private final AgentService service = new AgentService(loop, repository, Clock.systemUTC());

    private TaskRecord record(String id, String createdAt) {
        return new TaskRecord(id, "task " + id, "answer " + id, List.of(),
                new TaskRollup(1, 1, 1, 1), Instant.parse(createdAt));
    }

    @Test
    void ordersNewestFirstAndSlicesPages() {
        when(repository.findAll()).thenReturn(List.of(
                record("b", "2026-07-01T10:00:00Z"),
                record("a", "2026-07-01T12:00:00Z"),
                record("c", "2026-07-01T08:00:00Z")));

        PagedTasksResponse page0 = service.list(0, 2);
        assertThat(page0.page()).isEqualTo(0);
        assertThat(page0.size()).isEqualTo(2);
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page0.items()).extracting(TaskRecordDto::taskId).containsExactly("a", "b");

        // Last, partial page.
        PagedTasksResponse page1 = service.list(1, 2);
        assertThat(page1.items()).extracting(TaskRecordDto::taskId).containsExactly("c");
        assertThat(page1.totalPages()).isEqualTo(2);
    }

    @Test
    void tieBreaksByTaskIdAscendingWhenCreatedAtEqual() {
        when(repository.findAll()).thenReturn(List.of(
                record("y", "2026-07-01T10:00:00Z"),
                record("x", "2026-07-01T10:00:00Z")));

        PagedTasksResponse page = service.list(0, 10);

        assertThat(page.items()).extracting(TaskRecordDto::taskId).containsExactly("x", "y");
    }

    @Test
    void outOfRangePageIsEmptyButKeepsCorrectTotals() {
        when(repository.findAll()).thenReturn(List.of(
                record("a", "2026-07-01T12:00:00Z"),
                record("b", "2026-07-01T11:00:00Z")));

        PagedTasksResponse page = service.list(5, 20);

        assertThat(page.items()).isEmpty();
        assertThat(page.page()).isEqualTo(5);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    @Test
    void emptyStoreHasNoItemsAndZeroPages() {
        when(repository.findAll()).thenReturn(List.of());

        PagedTasksResponse page = service.list(0, 20);

        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
    }
}

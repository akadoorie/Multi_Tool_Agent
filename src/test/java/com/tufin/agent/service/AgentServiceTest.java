package com.tufin.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tufin.agent.TestFixtures;
import com.tufin.agent.agent.AgentLoop;
import com.tufin.agent.agent.LoopResult;
import com.tufin.agent.agent.PriorTurn;
import com.tufin.agent.dto.ConversationSummaryDto;
import com.tufin.agent.dto.ConversationTurnDto;
import com.tufin.agent.dto.PagedConversationResponse;
import com.tufin.agent.dto.PagedConversationSummaries;
import com.tufin.agent.dto.PagedTasksResponse;
import com.tufin.agent.dto.TaskRecordDto;
import com.tufin.agent.exception.ConversationNotFoundException;
import com.tufin.agent.model.ConversationSummary;
import com.tufin.agent.model.TaskRecord;
import com.tufin.agent.model.TaskRollup;
import com.tufin.agent.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link AgentService} orchestration against a mocked repository + loop: paging/ordering for
 * {@code list}, and the conversation contract (new / continue / unknown / capped context) for
 * {@code solve} and {@code getConversation}. {@code TestFixtures.props} sets {@code contextTurns = 10}.
 */
class AgentServiceTest {

    private final AgentLoop loop = mock(AgentLoop.class);
    private final TaskRepository repository = mock(TaskRepository.class);
    private final AgentService service =
            new AgentService(loop, repository, Clock.systemUTC(), TestFixtures.props(8));

    private TaskRecord record(String id, String createdAt) {
        return new TaskRecord(id, "task " + id, "answer " + id, List.of(),
                new TaskRollup(1, 1, 1, 1, 0, 0), Instant.parse(createdAt), null, 0);
    }

    private TaskRecord convRecord(String id, String conversationId, int order, String task, String answer) {
        return new TaskRecord(id, task, answer, List.of(), new TaskRollup(1, 1, 1, 1, 0, 0),
                Instant.parse("2026-07-01T10:00:00Z"), conversationId, order);
    }

    private LoopResult loopResult(String answer) {
        return new LoopResult(answer, List.of(), new TaskRollup(1, 1, 1, 1, 0, 0));
    }

    // --- list() paging / ordering ------------------------------------------------------------

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

    // --- conversations -----------------------------------------------------------------------

    @Test
    void newTaskMintsAConversationAtOrderZeroWithNoHistory() {
        when(loop.run(eq("hello"), any())).thenReturn(loopResult("hi"));
        when(repository.save(any())).thenAnswer(inv -> ((TaskRecord) inv.getArgument(0)).withConversationOrder(0));

        TaskRecordDto dto = service.solve("hello", null);

        assertThat(dto.conversationId()).isNotBlank();
        assertThat(dto.conversationOrder()).isZero();
        verify(loop).run(eq("hello"), eq(List.of()));   // no prior context for a fresh conversation
    }

    @Test
    void followUpContinuesConversationAndFeedsPriorTurnAsContext() {
        when(repository.countByConversation("c1")).thenReturn(1);
        when(repository.findByConversation("c1", 10)).thenReturn(List.of(
                convRecord("t0", "c1", 0, "weather in Tel Aviv?", "30C")));
        when(loop.run(eq("to Fahrenheit"), any())).thenReturn(loopResult("86F"));
        when(repository.save(any())).thenAnswer(inv -> ((TaskRecord) inv.getArgument(0)).withConversationOrder(1));

        TaskRecordDto dto = service.solve("to Fahrenheit", "c1");

        assertThat(dto.conversationId()).isEqualTo("c1");
        assertThat(dto.conversationOrder()).isEqualTo(1);
        // The prior turn's task+answer are fed to the loop; the cap (contextTurns=10) is applied at the query.
        verify(repository).findByConversation("c1", 10);
        verify(loop).run(eq("to Fahrenheit"), eq(List.of(new PriorTurn("weather in Tel Aviv?", "30C"))));
    }

    @Test
    void unknownConversationIdOnSolveIs404() {
        when(repository.countByConversation("nope")).thenReturn(0);

        assertThatThrownBy(() -> service.solve("hi", "nope"))
                .isInstanceOf(ConversationNotFoundException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void getConversationReturnsPagedTurnsOldestFirst() {
        when(repository.countByConversation("c1")).thenReturn(3);
        when(repository.findConversationPage("c1", 0, 2)).thenReturn(List.of(
                convRecord("t0", "c1", 0, "q0", "a0"),
                convRecord("t1", "c1", 1, "q1", "a1")));

        PagedConversationResponse page = service.getConversation("c1", 0, 2);

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).extracting(ConversationTurnDto::conversationOrder).containsExactly(0, 1);
        assertThat(page.items()).extracting(ConversationTurnDto::task).containsExactly("q0", "q1");
    }

    @Test
    void getConversationUnknownIdIs404() {
        when(repository.countByConversation("nope")).thenReturn(0);

        assertThatThrownBy(() -> service.getConversation("nope", 0, 20))
                .isInstanceOf(ConversationNotFoundException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void listConversationsMapsTruncatesAndBuildsThePagedEnvelope() {
        String longTask = "a".repeat(150);
        when(repository.countConversations()).thenReturn(3);
        when(repository.findConversationsPage(0, 2)).thenReturn(List.of(
                new ConversationSummary("c1", longTask, 3, Instant.parse("2026-07-01T12:00:00Z")),
                new ConversationSummary("c2", "short opener", 1, Instant.parse("2026-07-01T11:00:00Z"))));

        PagedConversationSummaries page = service.listConversations(0, 2);

        assertThat(page.page()).isEqualTo(0);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);   // ceil(3 / 2)
        assertThat(page.items()).extracting(ConversationSummaryDto::conversationId).containsExactly("c1", "c2");
        ConversationSummaryDto first = page.items().get(0);
        assertThat(first.turnCount()).isEqualTo(3);
        assertThat(first.firstTask()).hasSize(101).endsWith("…");   // truncated to 100 chars + ellipsis
        assertThat(page.items().get(1).firstTask()).isEqualTo("short opener");   // short text is left as-is
    }
}

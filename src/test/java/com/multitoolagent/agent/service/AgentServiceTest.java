package com.multitoolagent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.multitoolagent.agent.TestFixtures;
import com.multitoolagent.agent.agent.AgentLoop;
import com.multitoolagent.agent.agent.llm.ILlmClient;
import com.multitoolagent.agent.agent.llm.LlmClientRegistry;
import com.multitoolagent.agent.agent.LoopResult;
import com.multitoolagent.agent.agent.PriorTurn;
import com.multitoolagent.agent.dto.ConversationSummaryDto;
import com.multitoolagent.agent.dto.ConversationTurnDto;
import com.multitoolagent.agent.dto.PagedConversationResponse;
import com.multitoolagent.agent.dto.PagedConversationSummaries;
import com.multitoolagent.agent.dto.PagedTasksResponse;
import com.multitoolagent.agent.dto.TaskRecordDto;
import com.multitoolagent.agent.exception.InvalidRequestException;
import com.multitoolagent.agent.exception.ConversationNotFoundException;
import com.multitoolagent.agent.model.ConversationSummary;
import com.multitoolagent.agent.model.TaskRecord;
import com.multitoolagent.agent.model.TaskRollup;
import com.multitoolagent.agent.repository.ITaskRepository;
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
    private final ITaskRepository repository = mock(ITaskRepository.class);
    private final ILlmClient springAi = fakeClient("spring-ai");
    private final ILlmClient langChain4j = fakeClient("langchain4j");
    private final LlmClientRegistry llmClients =
            new LlmClientRegistry(List.of(springAi, langChain4j), TestFixtures.props(8));
    private final AgentService service =
            new AgentService(loop, llmClients, repository, Clock.systemUTC(), TestFixtures.props(8));

    private static ILlmClient fakeClient(String id) {
        ILlmClient client = mock(ILlmClient.class);
        when(client.id()).thenReturn(id);
        return client;
    }

    private TaskRecord record(String id, String createdAt) {
        return new TaskRecord(id, "task " + id, "answer " + id, List.of(),
                new TaskRollup(1, 1, 1, 1, 0, 0), Instant.parse(createdAt), null, 0, "spring-ai");
    }

    private TaskRecord convRecord(String id, String conversationId, int order, String task, String answer) {
        return new TaskRecord(id, task, answer, List.of(), new TaskRollup(1, 1, 1, 1, 0, 0),
                Instant.parse("2026-07-01T10:00:00Z"), conversationId, order, "spring-ai");
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
        when(loop.run(eq("hello"), any(), any())).thenReturn(loopResult("hi"));
        when(repository.save(any())).thenAnswer(inv -> ((TaskRecord) inv.getArgument(0)).withConversationOrder(0));

        TaskRecordDto dto = service.solve("hello", null, null);

        assertThat(dto.conversationId()).isNotBlank();
        assertThat(dto.conversationOrder()).isZero();
        // No prior context for a fresh conversation; no engine named -> the default (spring-ai).
        verify(loop).run(eq("hello"), eq(List.of()), eq(springAi));
        assertThat(dto.engine()).isEqualTo("spring-ai");
    }

    @Test
    void runsTheTaskWithTheRequestedEngineAndRecordsIt() {
        when(loop.run(eq("hello"), any(), any())).thenReturn(loopResult("hi"));
        when(repository.save(any())).thenAnswer(inv -> ((TaskRecord) inv.getArgument(0)).withConversationOrder(0));

        TaskRecordDto dto = service.solve("hello", null, "langchain4j");

        verify(loop).run(eq("hello"), eq(List.of()), eq(langChain4j));
        assertThat(dto.engine()).isEqualTo("langchain4j");
    }

    @Test
    void unknownEngineIs400BeforeAnyWork() {
        assertThatThrownBy(() -> service.solve("hello", null, "openai"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("openai")
                .hasMessageContaining("langchain4j, spring-ai");
        verifyNoInteractions(loop, repository);
    }

    @Test
    void followUpContinuesConversationAndFeedsPriorTurnAsContext() {
        when(repository.countByConversation("c1")).thenReturn(1);
        when(repository.findByConversation("c1", 10)).thenReturn(List.of(
                convRecord("t0", "c1", 0, "weather in Tel Aviv?", "30C")));
        when(loop.run(eq("to Fahrenheit"), any(), any())).thenReturn(loopResult("86F"));
        when(repository.save(any())).thenAnswer(inv -> ((TaskRecord) inv.getArgument(0)).withConversationOrder(1));

        // The earlier turn ran on spring-ai; this follow-up switches engine — history is plain text.
        TaskRecordDto dto = service.solve("to Fahrenheit", "c1", "langchain4j");

        assertThat(dto.conversationId()).isEqualTo("c1");
        assertThat(dto.conversationOrder()).isEqualTo(1);
        // The prior turn's task+answer are fed to the loop; the cap (contextTurns=10) is applied at the query.
        verify(repository).findByConversation("c1", 10);
        verify(loop).run(eq("to Fahrenheit"), eq(List.of(new PriorTurn("weather in Tel Aviv?", "30C"))),
                eq(langChain4j));
        assertThat(dto.engine()).isEqualTo("langchain4j");
    }

    @Test
    void unknownConversationIdOnSolveIs404() {
        when(repository.countByConversation("nope")).thenReturn(0);

        assertThatThrownBy(() -> service.solve("hi", "nope", null))
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

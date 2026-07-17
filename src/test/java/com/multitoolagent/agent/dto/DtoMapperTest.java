package com.multitoolagent.agent.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.multitoolagent.agent.model.TaskRecord;
import com.multitoolagent.agent.model.TaskRollup;
import com.multitoolagent.agent.model.TraceStep;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link DtoMapper#toConversationTurn} derives the lightweight conversation-turn view from a
 * stored record &mdash; in particular that the turn's {@code reasoning} is lifted out of the trace's
 * {@code reasoning} step (so {@code GET /conversations/{id}} shows the explanation behind the value),
 * and is {@code null} when the turn has no such step.
 */
class DtoMapperTest {

    private static final TaskRollup ROLLUP = new TaskRollup(1, 1, 1, 1, 0, 0);
    private static final Instant AT = Instant.parse("2026-07-01T10:00:00Z");

    private static TaskRecord record(String finalAnswer, List<TraceStep> trace) {
        return new TaskRecord("t0", "the task", finalAnswer, trace, ROLLUP, AT, "c1", 0);
    }

    @Test
    void liftsTheReasoningStepIntoTheConversationTurn() {
        // A tool-grounded answer whose final turn carried a caveat: web_search ran, then the answer was
        // split into a reasoning step (the caveat) and a clean final_answer (the value).
        List<TraceStep> trace = List.of(
                TraceStep.tool(1, 1, "web_search", null, null, 5),
                TraceStep.reasoning(2, 1, "The tool did not return the digits; used an earlier result", 3, null),
                TraceStep.finalAnswer(3, 1, List.of("web_search"), "3.14159", 0, null));

        ConversationTurnDto dto = DtoMapper.toConversationTurn(record("3.14159", trace));

        assertThat(dto.finalAnswer()).isEqualTo("3.14159");
        assertThat(dto.reasoning()).isEqualTo("The tool did not return the digits; used an earlier result");
        assertThat(dto.toolsUsed()).containsExactly("web_search");
    }

    @Test
    void leavesReasoningNullWhenTheTurnHasNoReasoningStep() {
        // A clean answer: just a final_answer step, no caveat — reasoning must be null (omitted from JSON).
        List<TraceStep> trace = List.of(
                TraceStep.tool(1, 1, "calculator", null, null, 2),
                TraceStep.finalAnswer(2, 1, List.of("calculator"), "36", 4, null));

        ConversationTurnDto dto = DtoMapper.toConversationTurn(record("36", trace));

        assertThat(dto.finalAnswer()).isEqualTo("36");
        assertThat(dto.reasoning()).isNull();
    }
}

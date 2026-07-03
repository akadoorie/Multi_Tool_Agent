package com.tufin.agent.dto;

import com.tufin.agent.model.TaskRecord;
import com.tufin.agent.model.TraceStep;
import java.util.List;

/** Maps the internal {@link TaskRecord} to the outward-facing DTOs. */
public final class DtoMapper {

    private DtoMapper() {
    }

    public static TaskRecordDto toDto(TaskRecord record) {
        return new TaskRecordDto(
                record.taskId(),
                record.task(),
                record.finalAnswer(),
                record.trace(),
                record.rollup(),
                record.conversationId(),
                record.conversationOrder());
    }

    /**
     * Maps a task to the lightweight {@link ConversationTurnDto} used by {@code GET /conversations}:
     * task + answer + the tools it used, but not the full trace (fetch that via {@code GET /tasks/id}).
     */
    public static ConversationTurnDto toConversationTurn(TaskRecord record) {
        return new ConversationTurnDto(
                record.conversationOrder(),
                record.taskId(),
                record.task(),
                record.finalAnswer(),
                toolsUsed(record),
                record.createdAt());
    }

    /** The {@code toolsUsed} recorded on the terminating {@code final_answer} step (null if none). */
    private static List<String> toolsUsed(TaskRecord record) {
        return record.trace().stream()
                .filter(step -> "final_answer".equals(step.type()))
                .map(TraceStep::toolsUsed)
                .filter(tools -> tools != null)
                .findFirst()
                .orElse(null);
    }
}

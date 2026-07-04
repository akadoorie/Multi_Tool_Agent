package com.tufin.agent.dto;

import com.tufin.agent.model.ConversationSummary;
import com.tufin.agent.model.TaskRecord;
import com.tufin.agent.model.TraceStep;
import java.util.List;

/** Maps the internal domain records to the outward-facing DTOs. */
public final class DtoMapper {

    /** Cap for a conversation card's {@code first_task} subject line. */
    private static final int FIRST_TASK_MAX_CHARS = 100;

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

    /**
     * Maps a {@link ConversationSummary} to its DTO, truncating {@code firstTask} to a subject-line
     * length (with an ellipsis when cut). The truncation is a presentation concern, so it lives here.
     */
    public static ConversationSummaryDto toConversationSummary(ConversationSummary summary) {
        return new ConversationSummaryDto(
                summary.conversationId(),
                truncate(summary.firstTask(), FIRST_TASK_MAX_CHARS),
                summary.turnCount(),
                summary.lastActivityAt());
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…";
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

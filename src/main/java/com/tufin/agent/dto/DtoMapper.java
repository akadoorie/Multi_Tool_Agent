package com.tufin.agent.dto;

import com.tufin.agent.model.TaskRecord;

/** Maps the internal {@link TaskRecord} to the outward-facing {@link TaskResponse}. */
public final class DtoMapper {

    private DtoMapper() {
    }

    public static TaskResponse toResponse(TaskRecord record) {
        return new TaskResponse(
                record.taskId(),
                record.finalAnswer(),
                record.trace(),
                record.rollup());
    }
}

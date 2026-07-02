package com.tufin.agent.dto;

import com.tufin.agent.model.TaskRecord;

/** Maps the internal {@link TaskRecord} to the outward-facing {@link TaskRecordDto}. */
public final class DtoMapper {

    private DtoMapper() {
    }

    public static TaskRecordDto toDto(TaskRecord record) {
        return new TaskRecordDto(
                record.taskId(),
                record.task(),
                record.finalAnswer(),
                record.trace(),
                record.rollup());
    }
}

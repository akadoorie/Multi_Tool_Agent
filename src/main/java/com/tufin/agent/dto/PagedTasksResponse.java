package com.tufin.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One page of stored tasks for {@code GET /tasks}, plus the paging metadata a client needs to navigate
 * the rest. Hand-rolled (rather than Spring Data's {@code Page}) so the JSON shape is small, stable,
 * and self-describing.
 *
 * @param items         the tasks on this page, newest first.
 * @param page          the 0-based page index that was returned.
 * @param size          the page size that was applied.
 * @param totalElements total number of stored tasks across all pages.
 * @param totalPages    total number of pages at this size ({@code 0} when there are no tasks).
 */
public record PagedTasksResponse(
        @Schema(description = "The tasks on this page, newest first.")
        List<TaskRecordDto> items,
        @Schema(description = "0-based index of this page.", example = "0")
        int page,
        @Schema(description = "Number of items per page.", example = "20")
        int size,
        @Schema(description = "Total number of stored tasks across all pages.", example = "137")
        long totalElements,
        @Schema(description = "Total number of pages at this size.", example = "7")
        int totalPages) {
}

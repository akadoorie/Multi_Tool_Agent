package com.tufin.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One page of a conversation's turns for {@code GET /conversations/{id}}, oldest first, plus the paging
 * metadata a client needs. Same hand-rolled shape as {@link PagedTasksResponse} (no Spring Data
 * {@code Page}).
 *
 * @param items         the turns on this page, ordered by {@code conversationOrder} ascending.
 * @param page          the 0-based page index that was returned.
 * @param size          the page size that was applied.
 * @param totalElements total number of turns in the conversation.
 * @param totalPages    total number of pages at this size.
 */
public record PagedConversationResponse(
        @Schema(description = "The turns on this page, oldest first.")
        List<ConversationTurnDto> items,
        @Schema(description = "0-based index of this page.", example = "0")
        int page,
        @Schema(description = "Number of items per page.", example = "20")
        int size,
        @Schema(description = "Total number of turns in the conversation.", example = "3")
        long totalElements,
        @Schema(description = "Total number of pages at this size.", example = "1")
        int totalPages) {
}

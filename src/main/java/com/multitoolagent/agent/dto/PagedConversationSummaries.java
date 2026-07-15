package com.multitoolagent.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One page of conversation summaries for {@code GET /conversations}, newest first by last activity,
 * plus the paging metadata a client needs to page through <em>all</em> conversations. Same hand-rolled
 * shape as {@link PagedTasksResponse}.
 *
 * <p>Distinct from {@link PagedConversationResponse}: this holds {@link ConversationSummaryDto} cards
 * (one per conversation), whereas that holds {@link ConversationTurnDto}s (the turns <em>of one</em>
 * conversation).
 *
 * @param items         the conversation cards on this page, newest first.
 * @param page          the 0-based page index that was returned.
 * @param size          the page size that was applied.
 * @param totalElements total number of conversations across all pages.
 * @param totalPages    total number of pages at this size ({@code 0} when there are no conversations).
 */
public record PagedConversationSummaries(
        @Schema(description = "The conversation cards on this page, newest first by last activity.")
        List<ConversationSummaryDto> items,
        @Schema(description = "0-based index of this page.", example = "0")
        int page,
        @Schema(description = "Number of items per page.", example = "20")
        int size,
        @Schema(description = "Total number of conversations across all pages.", example = "42")
        long totalElements,
        @Schema(description = "Total number of pages at this size.", example = "3")
        int totalPages) {
}

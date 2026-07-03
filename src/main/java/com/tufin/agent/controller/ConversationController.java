package com.tufin.agent.controller;

import com.tufin.agent.config.AppProperties;
import com.tufin.agent.dto.ApiError;
import com.tufin.agent.dto.PagedConversationResponse;
import com.tufin.agent.exception.InvalidRequestException;
import com.tufin.agent.service.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoint for reading a conversation's turns. HTTP concerns only. */
@RestController
public class ConversationController {

    private final AgentService service;
    private final AppProperties props;

    public ConversationController(AgentService service, AppProperties props) {
        this.service = service;
        this.props = props;
    }

    @Operation(
            summary = "List a conversation's turns (paginated)",
            description = "Returns the turns of a conversation, oldest first, as lightweight views. "
                    + "Fetch a turn's full trace via GET /tasks/{taskId}.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of turns",
                    content = @Content(schema = @Schema(implementation = PagedConversationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid page/size",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Unknown conversation id",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/conversations/{conversationId}")
    public PagedConversationResponse getConversation(
            @PathVariable String conversationId,
            @Parameter(description = "0-based page index (>= 0).")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Items per page. Defaults to app.pagination.default-size; "
                    + "capped at app.pagination.max-size.")
            @RequestParam(required = false) Integer size) {
        // Same config-driven paging validation as GET /tasks.
        int effectiveSize = (size == null) ? props.pagination().defaultSize() : size;
        if (page < 0 || effectiveSize < 1 || effectiveSize > props.pagination().maxSize()) {
            throw new InvalidRequestException(
                    "Invalid paging: page must be >= 0 and size must be between 1 and "
                            + props.pagination().maxSize() + ".");
        }
        return service.getConversation(conversationId, page, effectiveSize);
    }
}

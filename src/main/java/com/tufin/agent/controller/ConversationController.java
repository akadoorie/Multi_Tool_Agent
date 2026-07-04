package com.tufin.agent.controller;

import com.tufin.agent.config.AppProperties;
import com.tufin.agent.dto.ApiError;
import com.tufin.agent.dto.ConversationSummaryDto;
import com.tufin.agent.dto.PagedConversationResponse;
import com.tufin.agent.dto.TaskRecordDto;
import com.tufin.agent.dto.TaskRequest;
import com.tufin.agent.exception.InvalidRequestException;
import com.tufin.agent.service.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
            summary = "List recent conversations",
            description = "Returns the most recently active conversations, newest first, as lightweight "
                    + "cards. Each card's `first_task` is that conversation's opening task (a subject "
                    + "line) — NOT a generated summary. No LLM is involved.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recent conversations, newest first",
                    content = @Content(array = @ArraySchema(
                            schema = @Schema(implementation = ConversationSummaryDto.class)))),
            @ApiResponse(responseCode = "400", description = "Invalid limit",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/conversations")
    public List<ConversationSummaryDto> listConversations(
            @Parameter(description = "Max conversations to return. Defaults to app.conversation.list-default; "
                    + "capped at app.conversation.list-max.")
            @RequestParam(required = false) Integer limit) {
        // Config-driven validation, same shape as the paging params.
        int effectiveLimit = (limit == null) ? props.conversation().listDefault() : limit;
        if (effectiveLimit < 1 || effectiveLimit > props.conversation().listMax()) {
            throw new InvalidRequestException(
                    "Invalid limit: must be between 1 and " + props.conversation().listMax() + ".");
        }
        return service.listRecentConversations(effectiveLimit);
    }

    @Operation(
            summary = "Ask a follow-up in an existing conversation",
            description = "Submits a task that continues the given conversation, so the agent can "
                    + "reference earlier turns. The conversation id comes from a prior response's "
                    + "`conversation_id`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task solved",
                    content = @Content(schema = @Schema(implementation = TaskRecordDto.class))),
            @ApiResponse(responseCode = "400", description = "Blank/invalid task",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Unknown conversation id",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Agent could not complete the task",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/conversations/{conversationId}/task")
    public TaskRecordDto addTask(@PathVariable String conversationId,
            @Valid @RequestBody TaskRequest request) {
        // Continue the conversation; an unknown id -> ConversationNotFoundException -> 404 (in the service).
        return service.solve(request.task(), conversationId);
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

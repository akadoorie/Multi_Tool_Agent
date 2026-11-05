package com.multitoolagent.agent.controller;

import com.multitoolagent.agent.config.AppProperties;
import com.multitoolagent.agent.config.EngineParameterCustomizer;
import com.multitoolagent.agent.dto.ApiError;
import com.multitoolagent.agent.dto.PagedConversationResponse;
import com.multitoolagent.agent.dto.PagedConversationSummaries;
import com.multitoolagent.agent.dto.TaskRecordDto;
import com.multitoolagent.agent.dto.TaskRequest;
import com.multitoolagent.agent.exception.InvalidRequestException;
import com.multitoolagent.agent.service.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
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
            summary = "List conversations (paginated)",
            description = "Returns conversations newest-first by last activity, as lightweight cards, "
                    + "paginated so a client can page through all of them. Each card's `first_task` is "
                    + "that conversation's opening task (a subject line) — NOT a generated summary. No "
                    + "LLM is involved.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of conversation cards, newest first",
                    content = @Content(schema = @Schema(implementation = PagedConversationSummaries.class))),
            @ApiResponse(responseCode = "400", description = "Invalid page/size",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/conversations")
    public PagedConversationSummaries listConversations(
            @Parameter(description = "0-based page index (>= 0).")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Items per page. Defaults to app.pagination.default-size; "
                    + "capped at app.pagination.max-size.")
            @RequestParam(required = false) Integer size) {
        // Same config-driven paging validation as GET /tasks and GET /conversations/{id}.
        int effectiveSize = (size == null) ? props.pagination().defaultSize() : size;
        if (page < 0 || effectiveSize < 1 || effectiveSize > props.pagination().maxSize()) {
            throw new InvalidRequestException(
                    "Invalid paging: page must be >= 0 and size must be between 1 and "
                            + props.pagination().maxSize() + ".");
        }
        return service.listConversations(page, effectiveSize);
    }

    @Operation(
            summary = "Ask a follow-up in an existing conversation",
            description = "Submits a task that continues the given conversation, so the agent can "
                    + "reference earlier turns. The conversation id comes from a prior response's "
                    + "`conversation_id`. Each turn may use a different `engine` (query parameter).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task solved",
                    content = @Content(schema = @Schema(implementation = TaskRecordDto.class))),
            @ApiResponse(responseCode = "400", description = "Blank/invalid task or unknown engine",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Unknown conversation id",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Agent could not complete the task",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/conversations/{conversationId}/task")
    public TaskRecordDto addTask(@PathVariable String conversationId,
            @Valid @RequestBody TaskRequest request,
            @Parameter(description = EngineParameterCustomizer.DESCRIPTION)
            @RequestParam(name = EngineParameterCustomizer.PARAM, required = false) String engine) {
        // Continue the conversation; an unknown id -> ConversationNotFoundException -> 404 (in the service).
        return service.solve(request.task(), conversationId, engine);
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

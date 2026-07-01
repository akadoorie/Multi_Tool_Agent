package com.tufin.agent.controller;

import com.tufin.agent.dto.ApiError;
import com.tufin.agent.dto.TaskRequest;
import com.tufin.agent.dto.TaskResponse;
import com.tufin.agent.service.AgentService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for submitting tasks and reading stored results. HTTP concerns only. */
@RestController
public class TaskController {

    private final AgentService service;

    public TaskController(AgentService service) {
        this.service = service;
    }

    @Operation(
            summary = "Submit a task",
            description = "Runs the agent loop against the given natural-language task and returns the "
                    + "final answer plus the full reasoning/tool trace.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task solved",
                    content = @Content(schema = @Schema(implementation = TaskResponse.class))),
            @ApiResponse(responseCode = "400", description = "Blank/invalid task",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Agent could not complete the task",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/task")
    public TaskResponse createTask(@Valid @RequestBody TaskRequest request) {
        return service.solve(request.task());
    }

    @Operation(summary = "Fetch a stored task by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found",
                    content = @Content(schema = @Schema(implementation = TaskResponse.class))),
            @ApiResponse(responseCode = "404", description = "Unknown task id",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/tasks/{taskId}")
    public TaskResponse getTask(@PathVariable String taskId) {
        return service.get(taskId);
    }

    @Operation(summary = "List all stored tasks")
    @GetMapping("/tasks")
    public List<TaskResponse> listTasks() {
        return service.list();
    }
}

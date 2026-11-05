package com.multitoolagent.agent.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.multitoolagent.agent.dto.PagedTasksResponse;
import com.multitoolagent.agent.dto.TaskRecordDto;
import com.multitoolagent.agent.exception.InvalidRequestException;
import com.multitoolagent.agent.exception.TaskNotFoundException;
import com.multitoolagent.agent.model.TaskRollup;
import com.multitoolagent.agent.service.AgentService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Override the paging limits with DISTINCTIVE values (default 7, max 30 — not the app's 20/100) so the
// tests prove the controller reads them from config, not from any hardcoded constant.
@WebMvcTest(TaskController.class)
@TestPropertySource(properties = {"app.pagination.default-size=7", "app.pagination.max-size=30"})
class TaskControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AgentService service;

    private TaskRecordDto sample(String id) {
        return new TaskRecordDto(id, "what is 2+2", "42", List.of(), new TaskRollup(1, 1, 1, 1, 0, 0),
                "conv-1", 0, "spring-ai");
    }

    @Test
    void postTaskStartsANewConversationAndReturnsIt() throws Exception {
        when(service.solve("what is 2+2", null, null)).thenReturn(sample("id-1"));

        mockMvc.perform(post("/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"what is 2+2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_id").value("id-1"))
                .andExpect(jsonPath("$.task").value("what is 2+2"))
                .andExpect(jsonPath("$.final_answer").value("42"))
                .andExpect(jsonPath("$.conversation_id").value("conv-1"))
                .andExpect(jsonPath("$.conversation_order").value(0))
                .andExpect(jsonPath("$.engine").value("spring-ai"));

        verify(service).solve("what is 2+2", null, null);   // no conversation, default engine
    }

    @Test
    void postTaskPassesTheEngineQueryParameterThrough() throws Exception {
        when(service.solve("what is 2+2", null, "langchain4j")).thenReturn(sample("id-1"));

        mockMvc.perform(post("/task").param("engine", "langchain4j")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"what is 2+2\"}"))
                .andExpect(status().isOk());

        verify(service).solve("what is 2+2", null, "langchain4j");
    }

    @Test
    void postTaskWithUnknownEngineReturns400() throws Exception {
        when(service.solve("what is 2+2", null, "foo"))
                .thenThrow(new InvalidRequestException("Unknown engine 'foo'. Valid engines: [langchain4j, spring-ai]."));

        mockMvc.perform(post("/task").param("engine", "foo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"what is 2+2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void postBlankTaskReturns400() throws Exception {
        mockMvc.perform(post("/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void malformedJsonBodyReturns400() throws Exception {
        // The exact bad payload a user hit in Swagger: a missing quote makes the value `20^2` unparseable.
        // That's a client error and must be a 400, not a 500 from the catch-all handler.
        mockMvc.perform(post("/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"task\": 20^2\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void getTaskRoundTripsAndReturns404ForUnknownId() throws Exception {
        when(service.get("id-1")).thenReturn(sample("id-1"));
        when(service.get("nope")).thenThrow(new TaskNotFoundException("nope"));

        mockMvc.perform(get("/tasks/id-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_id").value("id-1"))
                .andExpect(jsonPath("$.task").value("what is 2+2"));

        mockMvc.perform(get("/tasks/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void listTasksFallsBackToConfiguredDefaultSizeWhenOmitted() throws Exception {
        // Configured default is 7 (see Config), so an omitted size must reach the service as size 7.
        when(service.list(0, 7)).thenReturn(new PagedTasksResponse(List.of(sample("id-1")), 0, 7, 1, 1));

        mockMvc.perform(get("/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(7))
                .andExpect(jsonPath("$.items[0].task_id").value("id-1"))
                .andExpect(jsonPath("$.items[0].task").value("what is 2+2"));

        verify(service).list(0, 7);
    }

    @Test
    void listTasksPassesPageAndSizeThrough() throws Exception {
        when(service.list(2, 5)).thenReturn(new PagedTasksResponse(List.of(), 2, 5, 11, 3));

        mockMvc.perform(get("/tasks").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.total_pages").value(3))
                .andExpect(jsonPath("$.items").isEmpty());

        verify(service).list(2, 5);
    }

    @Test
    void listTasksRejectsInvalidPagingWith400() throws Exception {
        // Below the minimum, above the CONFIGURED max (30 — so 31 is rejected even though it's < the
        // old hardcoded 100), and a negative page are all 400.
        mockMvc.perform(get("/tasks").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(get("/tasks").param("size", "31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(get("/tasks").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}

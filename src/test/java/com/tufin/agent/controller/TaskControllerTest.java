package com.tufin.agent.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tufin.agent.dto.PagedTasksResponse;
import com.tufin.agent.dto.TaskRecordDto;
import com.tufin.agent.exception.TaskNotFoundException;
import com.tufin.agent.model.TaskRollup;
import com.tufin.agent.service.AgentService;
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
        return new TaskRecordDto(id, "what is 2+2", "42", List.of(), new TaskRollup(1, 1, 1, 1));
    }

    @Test
    void postTaskReturnsTaskIdAndAnswer() throws Exception {
        when(service.solve(anyString())).thenReturn(sample("id-1"));

        mockMvc.perform(post("/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"what is 2+2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_id").value("id-1"))
                .andExpect(jsonPath("$.task").value("what is 2+2"))
                .andExpect(jsonPath("$.final_answer").value("42"));
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

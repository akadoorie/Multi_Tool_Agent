package com.tufin.agent.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tufin.agent.dto.TaskResponse;
import com.tufin.agent.exception.TaskNotFoundException;
import com.tufin.agent.model.TaskRollup;
import com.tufin.agent.service.AgentService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TaskController.class)
class TaskControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AgentService service;

    private TaskResponse sample(String id) {
        return new TaskResponse(id, "42", List.of(), new TaskRollup(1, 1, 1, 1));
    }

    @Test
    void postTaskReturnsTaskIdAndAnswer() throws Exception {
        when(service.solve(anyString())).thenReturn(sample("id-1"));

        mockMvc.perform(post("/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"what is 2+2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_id").value("id-1"))
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
                .andExpect(jsonPath("$.task_id").value("id-1"));

        mockMvc.perform(get("/tasks/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}

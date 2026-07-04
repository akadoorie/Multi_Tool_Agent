package com.tufin.agent.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tufin.agent.dto.ConversationSummaryDto;
import com.tufin.agent.dto.ConversationTurnDto;
import com.tufin.agent.dto.PagedConversationResponse;
import com.tufin.agent.dto.PagedConversationSummaries;
import com.tufin.agent.dto.TaskRecordDto;
import com.tufin.agent.exception.ConversationNotFoundException;
import com.tufin.agent.model.TaskRollup;
import com.tufin.agent.service.AgentService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// default-size 7 / max-size 30 so tests prove config-driven paging (mirrors TaskControllerTest).
@WebMvcTest(ConversationController.class)
@TestPropertySource(properties = {"app.pagination.default-size=7", "app.pagination.max-size=30"})
class ConversationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AgentService service;

    // --- GET /conversations/{id} -------------------------------------------------------------

    @Test
    void returnsPagedTurnsWithDefaultSizeFromConfig() throws Exception {
        ConversationTurnDto turn = new ConversationTurnDto(0, "t0", "q0", "a0", List.of("weather"),
                Instant.parse("2026-07-01T10:00:00Z"));
        when(service.getConversation("c1", 0, 7))
                .thenReturn(new PagedConversationResponse(List.of(turn), 0, 7, 1, 1));

        mockMvc.perform(get("/conversations/c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_elements").value(1))
                .andExpect(jsonPath("$.items[0].conversation_order").value(0))
                .andExpect(jsonPath("$.items[0].task").value("q0"))
                .andExpect(jsonPath("$.items[0].tools_used[0]").value("weather"));
    }

    @Test
    void unknownConversationIs404() throws Exception {
        when(service.getConversation("nope", 0, 7)).thenThrow(new ConversationNotFoundException("nope"));

        mockMvc.perform(get("/conversations/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void invalidPagingIs400() throws Exception {
        mockMvc.perform(get("/conversations/c1").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(get("/conversations/c1").param("size", "31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // --- POST /conversations/{id}/task (follow-up) -------------------------------------------

    @Test
    void addTaskContinuesTheConversation() throws Exception {
        TaskRecordDto followUp = new TaskRecordDto("id-2", "and 20% of that?", "48", List.of(),
                new TaskRollup(1, 1, 1, 1, 0, 0), "c1", 1);
        when(service.solve("and 20% of that?", "c1")).thenReturn(followUp);

        mockMvc.perform(post("/conversations/c1/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"and 20% of that?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation_id").value("c1"))
                .andExpect(jsonPath("$.conversation_order").value(1));

        verify(service).solve("and 20% of that?", "c1");   // id comes from the path
    }

    @Test
    void addTaskUnknownConversationIs404() throws Exception {
        when(service.solve("hi", "nope")).thenThrow(new ConversationNotFoundException("nope"));

        mockMvc.perform(post("/conversations/nope/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"hi\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // --- GET /conversations (paginated list) -------------------------------------------------

    @Test
    void listConversationsUsesConfiguredDefaultSizeAndReturnsEnvelope() throws Exception {
        ConversationSummaryDto card = new ConversationSummaryDto("c1", "opening task", 2,
                Instant.parse("2026-07-01T12:00:00Z"));
        when(service.listConversations(0, 7))
                .thenReturn(new PagedConversationSummaries(List.of(card), 0, 7, 1, 1));

        mockMvc.perform(get("/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(7))
                .andExpect(jsonPath("$.total_elements").value(1))
                .andExpect(jsonPath("$.total_pages").value(1))
                .andExpect(jsonPath("$.items[0].conversation_id").value("c1"))
                .andExpect(jsonPath("$.items[0].first_task").value("opening task"))
                .andExpect(jsonPath("$.items[0].turn_count").value(2));

        verify(service).listConversations(0, 7);   // default-size from config (7)
    }

    @Test
    void listConversationsRejectsInvalidPagingWith400() throws Exception {
        mockMvc.perform(get("/conversations").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(get("/conversations").param("size", "31"))   // > max-size (30)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(get("/conversations").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}

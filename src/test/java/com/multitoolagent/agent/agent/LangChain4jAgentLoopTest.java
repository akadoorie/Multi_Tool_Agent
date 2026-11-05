package com.multitoolagent.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.multitoolagent.agent.agent.llm.ILlmClient;
import com.multitoolagent.agent.agent.llm.langchain4j.LangChain4jLlmClient;
import com.multitoolagent.agent.agent.llm.langchain4j.LangChain4jToolSpecs;
import com.multitoolagent.agent.tool.ToolRegistry;
import com.multitoolagent.agent.util.JsonSupport;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** Runs every {@link AgentLoopTest} scenario through the <b>LangChain4j</b> engine. */
class LangChain4jAgentLoopTest extends AgentLoopTest {

    private final ChatModel chatModel = mock(ChatModel.class);

    @Override
    protected ILlmClient newClient(ToolRegistry registry, JsonSupport json) {
        return new LangChain4jLlmClient(chatModel, new LangChain4jToolSpecs(registry, json));
    }

    @Override
    protected void script(List<Turn> turns) {
        List<ChatResponse> responses = turns.stream().map(LangChain4jAgentLoopTest::toResponse).toList();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(responses.get(0),
                responses.subList(1, responses.size()).toArray(ChatResponse[]::new));
    }

    @Override
    protected int modelCalls() {
        return mockingDetails(chatModel).getInvocations().size();
    }

    @Override
    protected List<String> toolResultIdsSentOnLastCall() {
        return lastRequest().messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(message -> ((ToolExecutionResultMessage) message).id())
                .toList();
    }

    private ChatRequest lastRequest() {
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        Mockito.verify(chatModel, Mockito.atLeastOnce()).chat(request.capture());
        return request.getValue();
    }

    /** Neutral turn &rarr; LangChain4j response (usage always carries the Anthropic cache counts). */
    private static ChatResponse toResponse(Turn turn) {
        List<ToolExecutionRequest> requests = turn.calls().stream()
                .map(c -> ToolExecutionRequest.builder().id(c.id()).name(c.name()).arguments(c.argumentsJson()).build())
                .toList();
        AiMessage message = requests.isEmpty()
                ? AiMessage.from(turn.text())
                : AiMessage.from(turn.text(), requests);
        AnthropicTokenUsage usage = AnthropicTokenUsage.builder()
                .inputTokenCount(turn.in())
                .outputTokenCount(turn.out())
                .cacheCreationInputTokens(turn.cacheCreation())
                .cacheReadInputTokens(turn.cacheRead())
                .build();
        return ChatResponse.builder().aiMessage(message).tokenUsage(usage).build();
    }

    // --- LangChain4j-only behaviour -------------------------------------------------------------

    @Test
    void advertisesTheToolsOnEveryRequest() {
        script(List.of(textTurn("out of scope", 1, 1)));

        new AgentLoop(registry, promptFactory, json, com.multitoolagent.agent.TestFixtures.props(8))
                .run("anything", newClient(registry, json));

        assertThat(lastRequest().toolSpecifications())
                .extracting(spec -> spec.name())
                .containsExactly("calculator");
    }
}

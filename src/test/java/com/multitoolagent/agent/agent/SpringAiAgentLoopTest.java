package com.multitoolagent.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.multitoolagent.agent.TestFixtures;
import com.multitoolagent.agent.agent.llm.ILlmClient;
import com.multitoolagent.agent.agent.llm.springai.ChatOptionsFactory;
import com.multitoolagent.agent.agent.llm.springai.SpringAiLlmClient;
import com.multitoolagent.agent.model.TraceStep;
import com.multitoolagent.agent.tool.ToolRegistry;
import com.multitoolagent.agent.tool.ToolResult;
import com.multitoolagent.agent.util.JsonSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** Runs every {@link AgentLoopTest} scenario through the <b>Spring AI</b> engine. */
class SpringAiAgentLoopTest extends AgentLoopTest {

    private final ChatModel chatModel = mock(ChatModel.class);

    @Override
    protected ILlmClient newClient(ToolRegistry registry, JsonSupport json) {
        return new SpringAiLlmClient(chatModel, new ChatOptionsFactory(TestFixtures.props(8), registry));
    }

    @Override
    protected void script(List<Turn> turns) {
        List<ChatResponse> responses = turns.stream().map(SpringAiAgentLoopTest::toResponse).toList();
        when(chatModel.call(any(Prompt.class))).thenReturn(responses.get(0),
                responses.subList(1, responses.size()).toArray(ChatResponse[]::new));
    }

    @Override
    protected int modelCalls() {
        return mockingDetails(chatModel).getInvocations().size();
    }

    @Override
    protected List<String> toolResultIdsSentOnLastCall() {
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        Mockito.verify(chatModel, Mockito.atLeastOnce()).call(prompt.capture());
        List<Message> sent = prompt.getValue().getInstructions();
        return sent.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::id)
                .toList();
    }

    /** Neutral turn &rarr; Spring AI response, with Anthropic-native usage only when cache counts exist. */
    private static ChatResponse toResponse(Turn turn) {
        AssistantMessage message = AssistantMessage.builder()
                .content(turn.text())
                .toolCalls(turn.calls().stream()
                        .map(c -> new AssistantMessage.ToolCall(c.id(), "function", c.name(), c.argumentsJson()))
                        .toList())
                .build();
        DefaultUsage usage = (turn.cacheCreation() == 0 && turn.cacheRead() == 0)
                ? new DefaultUsage(turn.in(), turn.out(), turn.in() + turn.out())
                : new DefaultUsage(turn.in(), turn.out(), turn.in() + turn.out(),
                        new AnthropicApi.Usage(turn.in(), turn.out(), turn.cacheCreation(), turn.cacheRead()));
        return new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder().usage(usage).build());
    }

    // --- Spring-AI-only behaviour -------------------------------------------------------------

    @Test
    void aggregatesToolCallsSplitAcrossGenerations() {
        // Reproduces the real Spring AI Anthropic behaviour: one tool_use turn arrives split across
        // two Generations (an empty leading one, then the tool call). The client must still report it.
        AssistantMessage empty = AssistantMessage.builder().content("").build();
        AssistantMessage withCall = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "calculator",
                        "{\"expression\":\"17 * 23\"}")))
                .build();
        ChatResponse split = new ChatResponse(List.of(new Generation(empty), new Generation(withCall)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5, 15)).build());
        when(chatModel.call(any(Prompt.class))).thenReturn(split, toResponse(textTurn("The answer is 391.", 20, 7)));

        LoopResult result = new AgentLoop(registry, promptFactory, json, TestFixtures.props(8))
                .run("what is 17 * 23", newClient(registry, json));

        assertThat(result.finalAnswer()).isEqualTo("The answer is 391.");
        assertThat(result.trace()).extracting(TraceStep::type)
                .containsExactly("model_call", "tool", "final_answer");
        assertThat(result.trace().get(1).tool()).isEqualTo("calculator");
        assertThat(((ToolResult) result.trace().get(1).toolOutput()).ok()).isTrue();
        assertThat(modelCalls()).isEqualTo(2);
    }
}

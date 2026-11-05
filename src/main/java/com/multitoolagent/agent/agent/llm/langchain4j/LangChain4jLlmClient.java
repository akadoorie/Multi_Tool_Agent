package com.multitoolagent.agent.agent.llm.langchain4j;

import com.multitoolagent.agent.agent.llm.AgentMessage;
import com.multitoolagent.agent.agent.llm.ILlmClient;
import com.multitoolagent.agent.agent.llm.ModelTurn;
import com.multitoolagent.agent.model.TraceStep;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * {@link ILlmClient} backed by <b>LangChain4j</b>. Translates our neutral messages into LangChain4j's
 * message types, makes one {@link ChatModel#chat(ChatRequest)} call, and translates the reply back.
 *
 * <p>We call the low-level {@link ChatModel} directly (not LangChain4j's {@code AiServices}), so
 * LangChain4j only reports the tool calls and never runs them &mdash; {@code AgentLoop} does.
 */
@Component
public class LangChain4jLlmClient implements ILlmClient {

    public static final String ID = "langchain4j";

    private final ChatModel chatModel;
    private final LangChain4jToolSpecs toolSpecs;

    public LangChain4jLlmClient(@Qualifier("langChain4jChatModel") ChatModel chatModel,
            LangChain4jToolSpecs toolSpecs) {
        this.chatModel = chatModel;
        this.toolSpecs = toolSpecs;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModelTurn call(List<AgentMessage> conversation) {
        List<ChatMessage> messages = new ArrayList<>();
        for (AgentMessage message : conversation) {
            messages.addAll(toLangChain4j(message));
        }
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolSpecs.all())
                .build();
        ChatResponse response = chatModel.chat(request);

        AiMessage ai = response.aiMessage();
        String text = ai == null || ai.text() == null || ai.text().isBlank() ? null : ai.text();
        List<AgentMessage.ToolCall> toolCalls = ai == null || !ai.hasToolExecutionRequests()
                ? List.of()
                : ai.toolExecutionRequests().stream()
                        .map(r -> new AgentMessage.ToolCall(r.id(), r.name(), r.arguments()))
                        .toList();

        TokenUsage usage = response.tokenUsage();
        long cacheRead = 0;
        long cacheCreation = 0;
        // Cache counts are Anthropic-specific, so they live on the AnthropicTokenUsage subclass only.
        if (usage instanceof AnthropicTokenUsage anthropic) {
            cacheRead = nullToZero(anthropic.cacheReadInputTokens());
            cacheCreation = nullToZero(anthropic.cacheCreationInputTokens());
        }
        return new ModelTurn(text, toolCalls, tokenUsage(usage), cacheRead, cacheCreation);
    }

    /**
     * Neutral message &rarr; LangChain4j message(s). One neutral {@code ToolResults} becomes one
     * {@link ToolExecutionResultMessage} per tool (that is LangChain4j's shape; its Anthropic mapper
     * groups consecutive results back into a single user turn on the wire).
     */
    private static List<ChatMessage> toLangChain4j(AgentMessage message) {
        return switch (message) {
            case AgentMessage.System system -> List.of(SystemMessage.from(system.text()));
            case AgentMessage.User user -> List.of(UserMessage.from(user.text()));
            case AgentMessage.Assistant assistant -> List.of(toAiMessage(assistant));
            case AgentMessage.ToolResults results -> results.responses().stream()
                    .map(r -> (ChatMessage) ToolExecutionResultMessage.from(r.id(), r.name(), r.resultJson()))
                    .toList();
        };
    }

    private static AiMessage toAiMessage(AgentMessage.Assistant assistant) {
        String text = assistant.text() == null ? "" : assistant.text();
        if (assistant.toolCalls().isEmpty()) {
            return AiMessage.from(text);
        }
        List<ToolExecutionRequest> requests = assistant.toolCalls().stream()
                .map(call -> ToolExecutionRequest.builder()
                        .id(call.id())
                        .name(call.name())
                        .arguments(call.argumentsJson())
                        .build())
                .toList();
        return AiMessage.from(text, requests);
    }

    private static TraceStep.TokenUsage tokenUsage(TokenUsage usage) {
        if (usage == null) {
            return new TraceStep.TokenUsage(0, 0, 0);
        }
        return new TraceStep.TokenUsage(
                nullToZero(usage.inputTokenCount()),
                nullToZero(usage.outputTokenCount()),
                nullToZero(usage.totalTokenCount()));
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}

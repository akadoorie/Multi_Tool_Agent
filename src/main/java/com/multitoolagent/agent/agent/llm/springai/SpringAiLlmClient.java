package com.multitoolagent.agent.agent.llm.springai;

import com.multitoolagent.agent.agent.llm.AgentMessage;
import com.multitoolagent.agent.agent.llm.ILlmClient;
import com.multitoolagent.agent.agent.llm.ModelTurn;
import com.multitoolagent.agent.model.TraceStep;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * {@link ILlmClient} backed by <b>Spring AI</b>. Translates our neutral messages into Spring AI's
 * message types, makes one {@link ChatModel} call, and translates the reply back. Tool execution is
 * disabled in the options ({@link ChatOptionsFactory}), so Spring AI only reports the tool calls.
 */
@Component
public class SpringAiLlmClient implements ILlmClient {

    public static final String ID = "spring-ai";

    private final ChatModel chatModel;
    private final ChatOptionsFactory optionsFactory;

    public SpringAiLlmClient(ChatModel chatModel, ChatOptionsFactory optionsFactory) {
        this.chatModel = chatModel;
        this.optionsFactory = optionsFactory;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModelTurn call(List<AgentMessage> conversation) {
        List<Message> messages = conversation.stream().map(SpringAiLlmClient::toSpringAi).toList();
        ChatResponse response = chatModel.call(new Prompt(messages, optionsFactory.build()));

        // Spring AI's Anthropic mapping can split ONE assistant turn across several Generations
        // (e.g. an empty leading text block plus a separate tool_use block). Reading only
        // response.getResult() (the first Generation) would miss tool calls that landed in a later
        // one, so we aggregate text and tool calls across every Generation.
        List<Generation> generations = response.getResults();
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        CacheTokens cache = cacheTokens(usage);
        return new ModelTurn(aggregateText(generations), aggregateToolCalls(generations),
                tokenUsage(usage), cache.read(), cache.creation());
    }

    /** Neutral message &rarr; Spring AI message. */
    private static Message toSpringAi(AgentMessage message) {
        return switch (message) {
            case AgentMessage.System system -> new SystemMessage(system.text());
            case AgentMessage.User user -> new UserMessage(user.text());
            case AgentMessage.Assistant assistant -> AssistantMessage.builder()
                    .content(assistant.text() == null ? "" : assistant.text())
                    .toolCalls(assistant.toolCalls().stream()
                            .map(call -> new AssistantMessage.ToolCall(
                                    call.id(), "function", call.name(), call.argumentsJson()))
                            .toList())
                    .build();
            case AgentMessage.ToolResults results -> ToolResponseMessage.builder()
                    .responses(results.responses().stream()
                            .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), r.resultJson()))
                            .toList())
                    .build();
        };
    }

    /** Joins the non-blank text from every Generation; returns null if the turn carried no text. */
    private static String aggregateText(List<Generation> generations) {
        String joined = generations.stream()
                .map(generation -> generation.getOutput().getText())
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n"));
        return joined.isBlank() ? null : joined;
    }

    /** Collects the tool calls from every Generation into one neutral list (never null). */
    private static List<AgentMessage.ToolCall> aggregateToolCalls(List<Generation> generations) {
        return generations.stream()
                .map(Generation::getOutput)
                .map(AssistantMessage::getToolCalls)
                .filter(calls -> calls != null)
                .flatMap(List::stream)
                .map(call -> new AgentMessage.ToolCall(call.id(), call.name(), call.arguments()))
                .toList();
    }

    /** Reads token usage defensively &mdash; some responses may carry no usage metadata. */
    private static TraceStep.TokenUsage tokenUsage(Usage usage) {
        if (usage == null) {
            return new TraceStep.TokenUsage(0, 0, 0);
        }
        return new TraceStep.TokenUsage(
                asInt(usage.getPromptTokens()),
                asInt(usage.getCompletionTokens()),
                asInt(usage.getTotalTokens()));
    }

    /**
     * Reads Anthropic prompt-cache token counts. These are not on Spring AI's
     * generic {@link Usage}; they live only on {@link AnthropicApi.Usage}, reachable via
     * {@link Usage#getNativeUsage()}. Zeros when absent &mdash; e.g. when the prefix is under the
     * model's cache minimum, Anthropic returns no cache tokens (silently uncached).
     */
    private static CacheTokens cacheTokens(Usage usage) {
        Object nativeUsage = usage == null ? null : usage.getNativeUsage();
        if (nativeUsage instanceof AnthropicApi.Usage anthropic) {
            return new CacheTokens(asInt(anthropic.cacheReadInputTokens()),
                    asInt(anthropic.cacheCreationInputTokens()));
        }
        return new CacheTokens(0, 0);
    }

    /** Prompt-cache token counts for one model call. */
    private record CacheTokens(long read, long creation) {
    }

    /** Accepts whatever numeric type the Spring AI getters return (Integer or Long); null &rarr; 0. */
    private static int asInt(Number value) {
        return value == null ? 0 : value.intValue();
    }
}

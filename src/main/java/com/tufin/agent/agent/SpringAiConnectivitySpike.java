package com.tufin.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.tufin.agent.config.AppProperties;
import com.tufin.agent.tool.Tool;
import com.tufin.agent.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A throwaway spike that de-risks the version-sensitive Spring AI tool-calling path <em>before</em>
 * the real loop depends on it. It is disabled by default and only runs under the {@code spike}
 * profile (a real {@code ANTHROPIC_API_KEY} is required):
 *
 * <pre>ANTHROPIC_API_KEY=... ./mvnw spring-boot:run -Dspring-boot.run.profiles=spike</pre>
 *
 * <p>It confirms all four symbols the loop relies on exist and behave as expected on the pinned
 * Spring AI version:
 * <ol>
 *   <li>{@code AnthropicChatOptions.Builder#internalToolExecutionEnabled(false)} &mdash; the model
 *       returns tool-call requests instead of executing them;</li>
 *   <li>{@code AssistantMessage#getToolCalls()} &mdash; reading those requests;</li>
 *   <li>{@code new ToolResponseMessage.ToolResponse(id, name, json)} &mdash; feeding a result back;</li>
 *   <li>{@code Usage#getPromptTokens()} / {@code getCompletionTokens()} &mdash; reading token usage.</li>
 * </ol>
 *
 * <p>Compilation alone proves the symbols exist on the pinned version; a live run additionally proves
 * the runtime behaviour (that tool calls actually come back when internal execution is disabled).
 */
@Component
@Profile("spike")
public class SpringAiConnectivitySpike implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SpringAiConnectivitySpike.class);

    private final ChatModel chatModel;
    private final AppProperties props;

    public SpringAiConnectivitySpike(ChatModel chatModel, AppProperties props) {
        this.chatModel = chatModel;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        ToolCallback echoTool = ChatOptionsFactory.adapt(new EchoTool());

        ChatOptions options = AnthropicChatOptions.builder()
                .model(props.agent().model())
                .maxTokens(1024)
                .toolCallbacks(List.of(echoTool))
                .internalToolExecutionEnabled(false) // (1)
                .build();

        List<Message> conversation = new ArrayList<>();
        conversation.add(new UserMessage("Use the echo tool to echo the word 'hello'."));

        ChatResponse response = chatModel.call(new Prompt(conversation, options));
        AssistantMessage assistant = response.getResult().getOutput();

        List<AssistantMessage.ToolCall> toolCalls = assistant.getToolCalls(); // (2)
        Usage usage = response.getMetadata().getUsage();
        log.info("SPIKE tool-calls returned: {}", toolCalls);
        log.info("SPIKE usage prompt={} completion={}", usage.getPromptTokens(), usage.getCompletionTokens()); // (4)

        if (toolCalls != null && !toolCalls.isEmpty()) {
            AssistantMessage.ToolCall call = toolCalls.get(0);
            conversation.add(assistant);
            conversation.add(new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse(call.id(), call.name(), "{\"echo\":\"hello\"}")))); // (3)
            ChatResponse finalResponse = chatModel.call(new Prompt(conversation, options));
            log.info("SPIKE final answer: {}", finalResponse.getResult().getOutput().getText());
            log.info("SPIKE PASSED: all four Spring AI symbols behaved as expected.");
        } else {
            log.warn("SPIKE: no tool calls returned — inspect the model/prompt before trusting the loop.");
        }
    }

    /** A trivial in-line tool used only by the spike. */
    private static final class EchoTool implements Tool {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "Echo back the provided text. Input: {\"text\": \"...\"}.";
        }

        @Override
        public String inputSchema() {
            return "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},"
                    + "\"required\":[\"text\"]}";
        }

        @Override
        public ToolResult execute(JsonNode input) {
            return ToolResult.ok(Map.of("echo", input.path("text").asText("")));
        }
    }
}

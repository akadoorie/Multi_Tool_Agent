package com.tufin.agent.agent;

import com.tufin.agent.config.AppProperties;
import com.tufin.agent.tool.Tool;
import com.tufin.agent.tool.ToolRegistry;
import java.util.List;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * Builds the per-request {@link ChatOptions} for the model call. This is the single source of truth
 * for the model id and is where the model-aware sampling decision lives.
 *
 * <p>{@code temperature} is only sent when {@code app.agent.send-sampling-params} is true. Sonnet 4.6
 * accepts sampling params, so {@code temperature=0} gives low-variance output; Opus 4.8/4.7 reject
 * {@code temperature}/{@code top_p}/{@code top_k} with HTTP 400, so for those models the flag is
 * turned off and we rely on prompting instead of blindly sending a parameter the model rejects.
 */
@Component
public class ChatOptionsFactory {

    private final AppProperties props;
    private final List<ToolCallback> toolCallbacks;

    public ChatOptionsFactory(AppProperties props, ToolRegistry registry) {
        this.props = props;
        this.toolCallbacks = registry.all().stream()
                .map(SpringAiToolAdapter::new)
                .map(ToolCallback.class::cast)
                .toList();
    }

    public ChatOptions build() {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder()
                .model(props.agent().model())
                .maxTokens(props.agent().maxTokens())
                .toolCallbacks(toolCallbacks)
                // Own the reason->act->observe loop: Spring AI returns tool-call requests instead of
                // executing them, so we can time each step and build the trace.
                .internalToolExecutionEnabled(false);

        if (props.agent().sendSamplingParams()) {
            builder.temperature(props.agent().temperature());
        }
        return builder.build();
    }

    /** Exposed so the connectivity spike can advertise the same tool schemas. */
    List<ToolCallback> toolCallbacks() {
        return toolCallbacks;
    }

    /** Convenience for the spike: adapt any single tool without the registry. */
    static ToolCallback adapt(Tool tool) {
        return new SpringAiToolAdapter(tool);
    }
}

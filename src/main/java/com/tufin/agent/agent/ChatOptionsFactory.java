package com.tufin.agent.agent;

import com.tufin.agent.config.AppProperties;
import com.tufin.agent.tool.ITool;
import com.tufin.agent.tool.ToolRegistry;
import java.util.Comparator;
import java.util.List;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
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
 *
 * <p><b>Prompt caching:</b> the stable per-call prefix (tool definitions + system prompt) is marked
 * cacheable via {@link AnthropicCacheStrategy#SYSTEM_AND_TOOLS}, so repeat calls bill that prefix at
 * ~0.1x instead of full price. For the prefix to actually hit the cache it must be byte-identical
 * across requests: the system prompt is date-granular (see {@code SystemPromptFactory}) and the tools
 * are advertised in a stable (name-sorted) order. Note Anthropic silently skips caching a prefix
 * under a model-specific minimum size (no error, just no cache tokens); the rollup's cache-token
 * counts reveal whether it actually engaged.
 */
@Component
public class ChatOptionsFactory {

    private final AppProperties props;
    private final List<ToolCallback> toolCallbacks;

    public ChatOptionsFactory(AppProperties props, ToolRegistry registry) {
        this.props = props;
        // Stable (name-sorted) order so the cached tools prefix is byte-identical across requests;
        // tool registration order is otherwise undefined.
        this.toolCallbacks = registry.all().stream()
                .sorted(Comparator.comparing(ITool::name))
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
                .internalToolExecutionEnabled(false)
                // Cache the stable tools+system prefix (one ephemeral breakpoint on the last system
                // block). Per-request content (user task, tool results, history) stays after it.
                .cacheOptions(AnthropicCacheOptions.builder()
                        .strategy(AnthropicCacheStrategy.SYSTEM_AND_TOOLS)
                        .build());

        if (props.agent().sendSamplingParams()) {
            builder.temperature(props.agent().temperature());
        }
        return builder.build();
    }
}

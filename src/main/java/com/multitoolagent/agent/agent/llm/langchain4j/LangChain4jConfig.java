package com.multitoolagent.agent.agent.llm.langchain4j;

import com.multitoolagent.agent.config.AppProperties;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the LangChain4j Anthropic model by hand from our own {@code app.agent.*} settings, so both
 * engines use the same model id, token limit, sampling rule and API key. We use the plain
 * {@code langchain4j-anthropic} module (not a Spring Boot starter) to avoid any auto-configuration
 * overlapping with Spring AI's.
 */
@Configuration
public class LangChain4jConfig {

    /**
     * Named so it can't be confused with Spring AI's {@code ChatModel} bean (a different type anyway).
     *
     * <p>Prompt caching mirrors the Spring AI side: the tool definitions and the system prompt are
     * marked cacheable, so repeat calls bill that stable prefix at a fraction of the price.
     */
    @Bean
    public ChatModel langChain4jChatModel(AppProperties props) {
        return modelBuilder(props).build();
    }

    /**
     * The fully configured builder, exposed so a test can build the exact same model but point it at a
     * local fake server ({@code baseUrl}) to check the real request/response mapping without a key.
     */
    static AnthropicChatModel.AnthropicChatModelBuilder modelBuilder(AppProperties props) {
        AppProperties.Agent agent = props.agent();
        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                // LangChain4j rejects a blank key at build time; a placeholder keeps the app bootable
                // without a key (as Spring AI is) — a real call then fails with Anthropic's 401.
                .apiKey(agent.apiKey() == null || agent.apiKey().isBlank() ? "missing-api-key" : agent.apiKey())
                .modelName(agent.model())
                .maxTokens(agent.maxTokens())
                .cacheSystemMessages(true)
                .cacheTools(true);
        if (agent.sendSamplingParams()) {
            builder.temperature(agent.temperature());
        }
        return builder;
    }
}

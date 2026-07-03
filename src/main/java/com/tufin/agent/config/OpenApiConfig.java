package com.tufin.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger / OpenAPI metadata surfaced at {@code /swagger-ui.html} and {@code /v3/api-docs}. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI agentOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Multi-Tool Agent API")
                .description("""
                        Accepts a natural-language task, runs an LLM agent that reasons step-by-step \
                        and calls real tools (calculator, weather, web search, unit/currency \
                        conversion), and returns a final answer plus a structured trace of every \
                        reasoning step and tool call.""")
                .version("1.0.0"));
    }

    /**
     * Builds swagger-core's model resolver from the Spring-managed {@link ObjectMapper}, which carries
     * the {@code SNAKE_CASE} naming strategy. Without this, springdoc introspects field names directly
     * and renders the schema in camelCase &mdash; mismatching the real snake_case wire format (and, for
     * {@code POST /task}, misleading a client into sending {@code conversationId} that Jackson ignores).
     */
    @Bean
    ModelResolver modelResolver(ObjectMapper objectMapper) {
        return new ModelResolver(objectMapper);
    }
}

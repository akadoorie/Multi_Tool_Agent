package com.tufin.agent.config;

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
}

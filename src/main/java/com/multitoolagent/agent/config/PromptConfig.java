package com.multitoolagent.agent.config;

import com.multitoolagent.agent.agent.SystemPromptFactory;
import com.multitoolagent.agent.tool.ToolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Wires the {@link SystemPromptFactory} bean, reading its prompt template from the configurable
 * location {@code app.agent.system-prompt-location} <b>once at startup</b>. Doing the file I/O here
 * (rather than inside the factory) keeps {@code SystemPromptFactory} a pure, easily-tested class that
 * only interpolates the loaded template &mdash; and means a missing or unreadable prompt fails the
 * whole application fast at boot, not lazily on the first request.
 */
@Configuration
public class PromptConfig {

    /**
     * Reads the system-prompt template from {@code app.agent.system-prompt-location} (a Spring
     * resource location, so {@code classpath:} and {@code file:} both work) and hands the loaded text
     * to a {@link SystemPromptFactory}. The read happens exactly once, at bean creation.
     *
     * @throws IllegalStateException if the configured prompt resource cannot be read, so the context
     *                               fails to start with a clear message instead of serving requests
     *                               with no system prompt.
     */
    @Bean
    SystemPromptFactory systemPromptFactory(Clock clock, ToolRegistry registry,
            ResourceLoader resourceLoader, AppProperties props) {
        String location = props.agent().systemPromptLocation();
        Resource resource = resourceLoader.getResource(location);
        try {
            String template = resource.getContentAsString(StandardCharsets.UTF_8);
            return new SystemPromptFactory(clock, registry, template);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load system prompt from " + location, e);
        }
    }
}

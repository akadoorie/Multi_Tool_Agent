package com.multitoolagent.agent;

import com.multitoolagent.agent.config.AppProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Application entry point for the Multi-Tool Agent REST API.
 *
 * <p>The service accepts a natural-language task, runs an explicit reason&rarr;act&rarr;observe loop
 * against Claude (via Spring AI), calls real tools, and returns a final answer plus a structured
 * trace of every step. All activity is persisted to a queryable JSON Lines file.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }

    /**
     * A single {@link Clock} bean so the "current date/time" injected into the system prompt and the
     * timestamp on stored records are deterministic and injectable in tests.
     */
    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}

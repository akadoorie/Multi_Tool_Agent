package com.multitoolagent.agent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed view of every tunable in {@code application.yml}, bound from the {@code app.*}
 * prefix. Keeping all knobs here (model, iteration cap, timeouts, tool URLs, data path) means there
 * are no magic numbers scattered through the code.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Agent agent, Http http, Tools tools, Persistence persistence,
        Pagination pagination, Conversation conversation) {

    /**
     * Agent / LLM settings.
     *
     * @param model                Claude model id (e.g. {@code claude-sonnet-4-6}).
     * @param maxIterations        hard cap on reason&rarr;act&rarr;observe cycles.
     * @param maxTokens            per-response output-token ceiling.
     * @param temperature          sampling temperature (only sent when {@code sendSamplingParams}).
     * @param sendSamplingParams   whether to send sampling params. Sonnet 4.6 accepts {@code temperature};
     *                             Opus 4.8/4.7 reject {@code temperature}/{@code top_p}/{@code top_k}
     *                             with HTTP 400, so switch this off for those models.
     * @param systemPromptLocation Spring resource location of the system-prompt template
     *                             ({@code classpath:…} or {@code file:…}). Read once at startup by
     *                             {@code PromptConfig}; the {@code {{tool_count}}}/{@code {{tool_catalog}}}/
     *                             {@code {{current_date}}} placeholders are filled in per request by
     *                             {@code SystemPromptFactory}.
     * @param defaultEngine        id of the LLM client used when a request doesn't name one
     *                             ({@code spring-ai} or {@code langchain4j}).
     * @param apiKey               Anthropic API key, shared by both engines (resolved from the environment).
     */
    public record Agent(
            String model,
            int maxIterations,
            int maxTokens,
            double temperature,
            boolean sendSamplingParams,
            String systemPromptLocation,
            String defaultEngine,
            String apiKey) {
    }

    /** Shared HTTP client timeouts, applied to every outbound tool call. */
    public record Http(Duration connectTimeout, Duration readTimeout) {
    }

    /** Base URLs for the keyless tool APIs. */
    public record Tools(Weather weather, Search search, Currency currency) {

        /** Open-Meteo endpoints (geocode a city, then fetch current conditions). */
        public record Weather(String geocodeUrl, String forecastUrl) {
        }

        /**
         * Web-search endpoints. DuckDuckGo instant answers are the fast path; Wikipedia REST is the
         * encyclopedic fallback when DuckDuckGo returns nothing.
         */
        public record Search(String ddgUrl, String wikiSearchUrl, String wikiSummaryUrl) {
        }

        /** Frankfurter live-rate currency API base URL. */
        public record Currency(String url) {
        }
    }

    /** File-persistence settings. */
    public record Persistence(String dataFile) {
    }

    /**
     * {@code GET /tasks} paging limits.
     *
     * @param defaultSize page size used when the caller omits {@code size}.
     * @param maxSize     largest {@code size} a caller may request (larger is rejected with 400).
     */
    public record Pagination(int defaultSize, int maxSize) {
    }

    /**
     * Multi-turn conversation settings.
     *
     * @param contextTurns how many of the most recent prior turns (task + answer) to resend as context
     *                     on a follow-up. Bounds the prompt so a long conversation never loads whole.
     */
    public record Conversation(int contextTurns) {
    }
}

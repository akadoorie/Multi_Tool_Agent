package com.multitoolagent.agent.config;

import java.util.List;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Provides the single, shared {@link RestClient} used by every tool. Centralising it gives us one
 * place to configure connect/read timeouts and lets the underlying connection pool be reused across
 * all outbound calls, instead of each tool spinning up its own client.
 */
@Configuration
public class HttpClientConfig {

    /**
     * A descriptive {@code User-Agent} sent on every outbound tool call. Some public APIs reject the
     * default/blank Java user-agent: Wikipedia's REST API (the web_search fallback) returns 403 for a
     * blank or {@code Java-http-client} agent, and DuckDuckGo blocks {@code Java-http-client} too. A
     * self-identifying agent (Wikimedia's policy asks for one) clears both.
     */
    private static final String USER_AGENT = "Multi-Tool-Agent/1.0 (Spring Boot educational project)";

    /**
     * A {@link RestClient} with the connect/read timeouts from {@code app.http.*} and a descriptive
     * {@code User-Agent}. Without timeouts a slow or hung third-party API would stall an agent
     * iteration indefinitely; without the user-agent, web_search would get a 403 (see {@link #USER_AGENT}).
     */
    @Bean
    RestClient toolRestClient(AppProperties props, RestClient.Builder builder) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(props.http().connectTimeout())
                .withReadTimeout(props.http().readTimeout());
        return builder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .messageConverters(converters -> converters.addFirst(javascriptTolerantJsonConverter()))
                .build();
    }

    /**
     * DuckDuckGo's Instant Answer API serves JSON with a {@code Content-Type} of
     * {@code application/x-javascript}, which the stock Jackson converter refuses to read. This
     * converter accepts those JS content types (as well as normal JSON) so the body still deserialises.
     */
    private static MappingJackson2HttpMessageConverter javascriptTolerantJsonConverter() {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setSupportedMediaTypes(List.of(
                MediaType.APPLICATION_JSON,
                MediaType.parseMediaType("application/x-javascript"),
                MediaType.parseMediaType("text/javascript"),
                MediaType.parseMediaType("application/javascript")));
        return converter;
    }
}

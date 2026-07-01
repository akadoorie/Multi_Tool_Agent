package com.tufin.agent.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Provides the single, shared {@link RestClient} used by every tool. Centralising it gives us one
 * place to configure connect/read timeouts and lets the underlying connection pool be reused across
 * all outbound calls, instead of each tool spinning up its own client.
 */
@Configuration
public class HttpClientConfig {

    /**
     * A {@link RestClient} with the connect/read timeouts from {@code app.http.*}. Without timeouts a
     * slow or hung third-party API would stall an agent iteration indefinitely.
     */
    @Bean
    RestClient toolRestClient(AppProperties props, RestClient.Builder builder) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(props.http().connectTimeout())
                .withReadTimeout(props.http().readTimeout());
        return builder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}

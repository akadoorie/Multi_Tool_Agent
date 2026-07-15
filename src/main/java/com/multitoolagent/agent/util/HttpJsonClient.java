package com.multitoolagent.agent.util;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Small seam over the shared {@link RestClient} for GET-and-parse-JSON calls. Tools depend on this
 * instead of {@link RestClient} directly for two reasons:
 *
 * <ul>
 *   <li>URI variables passed here are expanded and URL-encoded automatically, so raw user input
 *       (a city name, a search query) can never break out of the query string.</li>
 *   <li>It is trivial to mock in unit tests, letting us assert tool behaviour against a stubbed API
 *       response without any live network call.</li>
 * </ul>
 */
@Component
public class HttpJsonClient {

    private final RestClient restClient;

    public HttpJsonClient(RestClient toolRestClient) {
        this.restClient = toolRestClient;
    }

    /**
     * Issues a GET against the given URI template, expanding {@code {name}} placeholders from
     * {@code uriVariables} (values are URL-encoded), and parses the response body as a JSON tree.
     *
     * @return the parsed body, or {@code null} if the response had no body.
     */
    public JsonNode getJson(String uriTemplate, Map<String, ?> uriVariables) {
        return restClient.get()
                .uri(uriTemplate, uriVariables)
                .retrieve()
                .body(JsonNode.class);
    }
}

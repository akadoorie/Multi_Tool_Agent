package com.tufin.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tufin.agent.TestFixtures;
import com.tufin.agent.util.HttpJsonClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exercises the DuckDuckGo &rarr; Wikipedia fallback chain. */
class WebSearchToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpJsonClient http = mock(HttpJsonClient.class);
    private final WebSearchTool tool = new WebSearchTool(TestFixtures.props(8), http);

    private JsonNode input(String query) {
        return mapper.valueToTree(Map.of("query", query));
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubDuckDuckGo(JsonNode response) {
        when(http.getJson(argThat(uri -> uri != null && uri.contains("duckduckgo")), anyMap()))
                .thenReturn(response);
    }

    private void stubWikiSearch(JsonNode response) {
        when(http.getJson(argThat(uri -> uri != null && uri.contains("/w/rest.php/v1/search/page")), anyMap()))
                .thenReturn(response);
    }

    private void stubWikiSummary(JsonNode response) {
        when(http.getJson(argThat(uri -> uri != null && uri.contains("/page/summary/")), anyMap()))
                .thenReturn(response);
    }

    @Test
    void usesDuckDuckGoWhenItHasAnAbstract() {
        stubDuckDuckGo(json("{\"AbstractText\":\"A telephone is a device.\","
                + "\"AbstractURL\":\"https://duckduckgo.com/telephone\",\"Heading\":\"Telephone\"}"));

        ToolResult result = tool.execute(input("what is a telephone"));

        assertThat(result.ok()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data.get("provider")).isEqualTo("duckduckgo.com");
        assertThat((String) data.get("summary")).contains("telephone");
    }

    @Test
    void fallsBackToWikipediaWhenDuckDuckGoIsEmpty() {
        stubDuckDuckGo(json("{\"AbstractText\":\"\"}"));
        stubWikiSearch(json("{\"pages\":[{\"key\":\"Alan_Turing\",\"title\":\"Alan Turing\","
                + "\"excerpt\":\"English mathematician\"}]}"));
        stubWikiSummary(json("{\"title\":\"Alan Turing\",\"extract\":\"Alan Turing was a mathematician "
                + "and computer scientist.\"}"));

        ToolResult result = tool.execute(input("who was alan turing"));

        assertThat(result.ok()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data.get("provider")).isEqualTo("wikipedia.org");
        assertThat((String) data.get("summary")).contains("Turing");
        assertThat((java.util.List<?>) data.get("sources")).isNotEmpty();
    }

    @Test
    void fallsBackToWikipediaWhenDuckDuckGoThrows() {
        // The DDG fast path is best-effort: if it errors (e.g. an HTTP/parse failure), the tool must
        // still fall back to Wikipedia rather than fail outright.
        when(http.getJson(argThat(uri -> uri != null && uri.contains("duckduckgo")), anyMap()))
                .thenThrow(new RuntimeException("boom"));
        stubWikiSearch(json("{\"pages\":[{\"key\":\"Alan_Turing\",\"title\":\"Alan Turing\","
                + "\"excerpt\":\"English mathematician\"}]}"));
        stubWikiSummary(json("{\"extract\":\"Alan Turing was a mathematician and computer scientist.\"}"));

        ToolResult result = tool.execute(input("who was alan turing"));

        assertThat(result.ok()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data.get("provider")).isEqualTo("wikipedia.org");
        assertThat((String) data.get("summary")).contains("Turing");
    }

    @Test
    void returnsErrorWhenBothProvidersAreEmpty() {
        stubDuckDuckGo(json("{\"AbstractText\":\"\"}"));
        stubWikiSearch(json("{\"pages\":[]}"));

        ToolResult result = tool.execute(input("asdfqwerty nonexistent term"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("No results");
    }
}

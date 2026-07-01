package com.tufin.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.tufin.agent.config.AppProperties;
import com.tufin.agent.util.HttpJsonClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Web search that returns a concise summary plus source references. DuckDuckGo's Instant Answer API
 * is the fast path, but it is empty for most real queries, so we fall back to Wikipedia's REST API
 * (search then page summary). Only when BOTH yield nothing do we report no results.
 *
 * <p>This is instant-answer / encyclopedic lookup, not a full search-engine result-page crawl.
 *
 * <p>Security: returned text is treated strictly as data. The agent's system prompt instructs the
 * model not to follow any instructions embedded in tool output (prompt-injection guard).
 */
@Component
public class WebSearchTool implements Tool {

    private static final int MAX_SUMMARY_CHARS = 600;
    private static final String WIKI_ARTICLE_BASE = "https://en.wikipedia.org/wiki/";

    private final AppProperties props;
    private final HttpJsonClient http;

    public WebSearchTool(AppProperties props, HttpJsonClient http) {
        this.props = props;
        this.http = http;
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the web for factual/encyclopedic information and return a concise summary with "
                + "sources. Use for general knowledge questions you cannot answer from the other tools. "
                + "Input: {\"query\": \"who invented the telephone\"}.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": {"type": "string", "description": "The search query."}
                  },
                  "required": ["query"]
                }""";
    }

    @Override
    public ToolResult execute(JsonNode input) {
        if (!input.hasNonNull("query")) {
            return ToolResult.error("Missing required field 'query'.");
        }
        String query = input.get("query").asText();

        ToolResult ddg = tryDuckDuckGo(query);
        if (ddg != null) {
            return ddg;
        }
        ToolResult wiki = tryWikipedia(query);
        if (wiki != null) {
            return wiki;
        }
        return ToolResult.error("No results found for query: '" + query + "'.");
    }

    /** Fast path: DuckDuckGo Instant Answer. Returns null when it has no abstract to offer. */
    private ToolResult tryDuckDuckGo(String query) {
        JsonNode body = http.getJson(
                props.tools().search().ddgUrl() + "?q={q}&format=json&no_html=1&skip_disambig=1",
                Map.of("q", query));
        if (body == null) {
            return null;
        }
        String abstractText = body.path("AbstractText").asText("");
        if (abstractText.isBlank()) {
            return null;
        }
        List<Map<String, String>> sources = new ArrayList<>();
        String url = body.path("AbstractURL").asText("");
        if (!url.isBlank()) {
            sources.add(source(body.path("Heading").asText(query), url));
        }
        return ToolResult.ok(result(query, "duckduckgo.com", truncate(abstractText), sources));
    }

    /** Fallback: Wikipedia REST search + page summary. Returns null when nothing is found. */
    private ToolResult tryWikipedia(String query) {
        JsonNode search = http.getJson(
                props.tools().search().wikiSearchUrl() + "?q={q}&limit=3",
                Map.of("q", query));
        if (search == null || !search.hasNonNull("pages") || search.get("pages").isEmpty()) {
            return null;
        }

        JsonNode pages = search.get("pages");
        List<Map<String, String>> sources = new ArrayList<>();
        for (JsonNode page : pages) {
            String title = page.path("title").asText("");
            String key = page.path("key").asText(title);
            sources.add(source(title, WIKI_ARTICLE_BASE + key));
        }

        String topKey = pages.get(0).path("key").asText(pages.get(0).path("title").asText(""));
        JsonNode summary = http.getJson(
                props.tools().search().wikiSummaryUrl() + "{title}",
                Map.of("title", topKey));
        String extract = summary == null ? "" : summary.path("extract").asText("");
        if (extract.isBlank()) {
            extract = pages.get(0).path("excerpt").asText("");
        }
        if (extract.isBlank()) {
            return null;
        }
        return ToolResult.ok(result(query, "wikipedia.org", truncate(extract), sources));
    }

    private Map<String, Object> result(String query, String provider, String summary,
            List<Map<String, String>> sources) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("provider", provider);
        data.put("summary", summary);
        data.put("sources", sources);
        return data;
    }

    private Map<String, String> source(String title, String url) {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("title", title);
        s.put("url", url);
        return s;
    }

    private String truncate(String text) {
        String clean = text.strip();
        return clean.length() <= MAX_SUMMARY_CHARS ? clean : clean.substring(0, MAX_SUMMARY_CHARS) + "…";
    }
}

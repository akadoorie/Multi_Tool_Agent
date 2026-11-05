package com.multitoolagent.agent.agent.llm.langchain4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multitoolagent.agent.TestFixtures;
import com.multitoolagent.agent.agent.llm.AgentMessage;
import com.multitoolagent.agent.agent.llm.ModelTurn;
import com.multitoolagent.agent.tool.CalculatorTool;
import com.multitoolagent.agent.tool.ITool;
import com.multitoolagent.agent.tool.ToolRegistry;
import com.multitoolagent.agent.util.JsonSupport;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the <em>real</em> LangChain4j Anthropic model (built by {@link LangChain4jConfig}) against a
 * local fake Anthropic server. Unlike the mocked loop tests, this proves the actual HTTP (HyperText
 * Transfer Protocol) JSON we send and parse: tool schemas, prompt-cache markers, tool-result pairing and
 * cache-token usage &mdash; and that LangChain4j runs fine on the Jackson version Spring Boot pins.
 * No API key or network access needed.
 */
class LangChain4jWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private volatile String lastRequestBody;
    private volatile String nextResponseBody;

    @BeforeEach
    void startFakeAnthropic() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = nextResponseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFakeAnthropic() {
        server.stop(0);
    }

    private LangChain4jLlmClient client() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
        var model = LangChain4jConfig.modelBuilder(TestFixtures.props(8)).baseUrl(baseUrl).build();
        ToolRegistry registry = new ToolRegistry(List.of((ITool) new CalculatorTool()));
        return new LangChain4jLlmClient(model, new LangChain4jToolSpecs(registry, new JsonSupport(MAPPER)));
    }

    @Test
    void sendsCacheableToolsAndSystemPromptAndParsesAToolUseReply() throws Exception {
        nextResponseBody = """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-6",
                 "content":[{"type":"text","text":"Let me calculate."},
                            {"type":"tool_use","id":"toolu_1","name":"calculator","input":{"expression":"17*23"}}],
                 "stop_reason":"tool_use",
                 "usage":{"input_tokens":12,"output_tokens":8,
                          "cache_creation_input_tokens":1500,"cache_read_input_tokens":40}}
                """;

        ModelTurn turn = client().call(List.of(
                new AgentMessage.System("system prompt"),
                new AgentMessage.User("what is 17*23")));

        // Response mapping.
        assertThat(turn.text()).isEqualTo("Let me calculate.");
        assertThat(turn.toolCalls()).hasSize(1);
        AgentMessage.ToolCall call = turn.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("toolu_1");
        assertThat(call.name()).isEqualTo("calculator");
        // LangChain4j pretty-prints the arguments, so compare them as JSON, not as text.
        assertThat(MAPPER.readTree(call.argumentsJson())).isEqualTo(MAPPER.readTree("{\"expression\":\"17*23\"}"));
        assertThat(turn.usage().inputTokens()).isEqualTo(12);
        assertThat(turn.usage().outputTokens()).isEqualTo(8);
        assertThat(turn.cacheCreationTokens()).isEqualTo(1500);
        assertThat(turn.cacheReadTokens()).isEqualTo(40);

        // Request mapping.
        JsonNode request = MAPPER.readTree(lastRequestBody);
        assertThat(request.path("model").asText()).isEqualTo("claude-sonnet-4-6");
        JsonNode tool = request.path("tools").get(0);
        assertThat(tool.path("name").asText()).isEqualTo("calculator");
        // The tool's own JSON Schema reaches the wire unchanged (property schema + required list).
        JsonNode ourSchema = MAPPER.readTree(new CalculatorTool().inputSchema());
        assertThat(tool.path("input_schema").path("properties")).isEqualTo(ourSchema.path("properties"));
        assertThat(tool.path("input_schema").path("required")).isEqualTo(ourSchema.path("required"));
        // Prompt caching is on for both the tools and the system prompt.
        assertThat(tool.path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        JsonNode system = request.path("system");
        assertThat(system.get(system.size() - 1).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
    }

    @Test
    void sendsToolResultsBackPairedWithTheirCallIds() throws Exception {
        nextResponseBody = """
                {"id":"msg_2","type":"message","role":"assistant","model":"claude-sonnet-4-6",
                 "content":[{"type":"text","text":"391"}],
                 "stop_reason":"end_turn","usage":{"input_tokens":30,"output_tokens":2}}
                """;

        ModelTurn turn = client().call(List.of(
                new AgentMessage.System("system prompt"),
                new AgentMessage.User("what is 17*23"),
                new AgentMessage.Assistant("Let me calculate.", List.of(
                        new AgentMessage.ToolCall("toolu_1", "calculator", "{\"expression\":\"17*23\"}"))),
                new AgentMessage.ToolResults(List.of(
                        new AgentMessage.ToolResponse("toolu_1", "calculator", "{\"ok\":true,\"result\":391}")))));

        assertThat(turn.text()).isEqualTo("391");
        assertThat(turn.toolCalls()).isEmpty();
        assertThat(turn.cacheReadTokens()).isZero();

        JsonNode messages = MAPPER.readTree(lastRequestBody).path("messages");
        JsonNode assistant = messages.get(1);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        JsonNode toolUse = assistant.path("content").get(assistant.path("content").size() - 1);
        assertThat(toolUse.path("type").asText()).isEqualTo("tool_use");
        assertThat(toolUse.path("id").asText()).isEqualTo("toolu_1");

        JsonNode toolResult = messages.get(2).path("content").get(0);
        assertThat(messages.get(2).path("role").asText()).isEqualTo("user");
        assertThat(toolResult.path("type").asText()).isEqualTo("tool_result");
        assertThat(toolResult.path("tool_use_id").asText()).isEqualTo("toolu_1");
    }
}

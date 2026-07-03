package com.tufin.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tufin.agent.TestFixtures;
import com.tufin.agent.exception.AgentExecutionException;
import com.tufin.agent.model.TraceStep;
import com.tufin.agent.tool.CalculatorTool;
import com.tufin.agent.tool.Tool;
import com.tufin.agent.tool.ToolRegistry;
import com.tufin.agent.tool.ToolResult;
import com.tufin.agent.util.JsonSupport;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Tests the reason&rarr;act&rarr;observe loop against a mocked {@link ChatModel}, so the tests are
 * deterministic and exercise the orchestration we wrote &mdash; not the model's quality.
 */
class AgentLoopTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final ChatOptionsFactory optionsFactory = mock(ChatOptionsFactory.class);
    private final SystemPromptFactory promptFactory = mock(SystemPromptFactory.class);
    private final ToolRegistry registry = new ToolRegistry(List.of((Tool) new CalculatorTool()));
    private final JsonSupport json = new JsonSupport(new ObjectMapper());

    @BeforeEach
    void stubPrompt() {
        when(promptFactory.build()).thenReturn("system prompt");
    }

    private AgentLoop loop(int maxIterations) {
        return new AgentLoop(chatModel, registry, optionsFactory, promptFactory, json,
                TestFixtures.props(maxIterations));
    }

    @Test
    void dispatchesToolThenReturnsFinalAnswer() {
        when(chatModel.call(any(Prompt.class))).thenReturn(
                toolTurn("Let me calculate.", 10, 5, call("calculator", "{\"expression\":\"2 + 2 * 3\"}")),
                textTurn("The result is 8.", 20, 7));

        LoopResult result = loop(8).run("what is 2 + 2 * 3");

        assertThat(result.finalAnswer()).isEqualTo("The result is 8.");
        assertThat(result.trace()).extracting(TraceStep::type)
                .containsExactly("model_call", "tool", "final_answer");

        TraceStep toolStep = result.trace().get(1);
        assertThat(toolStep.tool()).isEqualTo("calculator");
        assertThat(((ToolResult) toolStep.toolOutput()).ok()).isTrue();
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void aggregatesToolCallsSplitAcrossGenerations() {
        // Reproduces the real Spring AI Anthropic behaviour: one tool_use turn arrives split across
        // two Generations (an empty leading one, then the tool call). The loop must still dispatch it.
        when(chatModel.call(any(Prompt.class))).thenReturn(
                splitToolTurn(10, 5, call("calculator", "{\"expression\":\"17 * 23\"}")),
                textTurn("The answer is 391.", 20, 7));

        LoopResult result = loop(8).run("what is 17 * 23");

        assertThat(result.finalAnswer()).isEqualTo("The answer is 391.");
        assertThat(result.trace()).extracting(TraceStep::type)
                .containsExactly("model_call", "tool", "final_answer");
        TraceStep toolStep = result.trace().get(1);
        assertThat(toolStep.tool()).isEqualTo("calculator");
        assertThat(((ToolResult) toolStep.toolOutput()).ok()).isTrue();
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void returnsUnsupportedWithReasoningStepWhenNoToolIsUsed() {
        // Scope gate: no tool used -> out of scope. The model's explanation is preserved as a
        // `reasoning` step and the verdict is a synthetic `final_answer` of exactly "Unsupported".
        String explanation = "Power conversion is out of scope; unit_converter only supports "
                + "length/weight/temperature/currency.";
        when(chatModel.call(any(Prompt.class))).thenReturn(textTurn(explanation, 5, 3));

        LoopResult result = loop(8).run("convert 1000000 watts to kilowatts");

        assertThat(result.finalAnswer()).isEqualTo("Unsupported");
        assertThat(result.trace()).extracting(TraceStep::type)
                .containsExactly("reasoning", "final_answer");

        TraceStep reasoning = result.trace().get(0);
        assertThat(reasoning.thought()).contains("out of scope").isNotEqualTo("Unsupported");

        TraceStep finalStep = result.trace().get(1);
        assertThat(finalStep.thought()).isEqualTo("Unsupported");
        assertThat(finalStep.latencyMs()).isZero();
        assertThat(finalStep.tokens()).isNull();

        assertThat(result.rollup().iterations()).isEqualTo(1);
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void returnsSingleUnsupportedStepWhenNoToolAndNoText() {
        // No tool AND no explanatory text -> just the synthetic final_answer, no reasoning step.
        when(chatModel.call(any(Prompt.class))).thenReturn(textTurn("", 5, 1));

        LoopResult result = loop(8).run("something out of scope");

        assertThat(result.finalAnswer()).isEqualTo("Unsupported");
        assertThat(result.trace()).extracting(TraceStep::type).containsExactly("final_answer");
        assertThat(result.trace().get(0).thought()).isEqualTo("Unsupported");
        assertThat(result.rollup().iterations()).isEqualTo(1);
    }

    @Test
    void recoversWhenAToolErrorIsFedBack() {
        when(chatModel.call(any(Prompt.class))).thenReturn(
                toolTurn("Trying a tool.", 8, 4, call("does_not_exist", "{}")),
                textTurn("Sorry, I could not complete that.", 6, 2));

        LoopResult result = loop(8).run("use a missing tool");

        TraceStep toolStep = result.trace().stream()
                .filter(step -> "tool".equals(step.type())).findFirst().orElseThrow();
        ToolResult output = (ToolResult) toolStep.toolOutput();
        assertThat(output.ok()).isFalse();
        assertThat(output.error()).contains("Unknown tool");
        assertThat(result.finalAnswer()).contains("could not");
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void throwsWhenMaxIterationsExhaustedInsteadOfFabricating() {
        // Always returns a tool call, so the loop can never terminate on a text turn.
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(toolTurn("looping", 1, 1, call("calculator", "{\"expression\":\"1+1\"}")));

        AgentLoop loop = loop(2);

        assertThatThrownBy(() -> loop.run("loop forever"))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("within 2 iterations");
    }

    @Test
    void sumsTokensOncePerIterationForTheRollup() {
        when(chatModel.call(any(Prompt.class))).thenReturn(
                toolTurn("calc", 10, 5, call("calculator", "{\"expression\":\"1+1\"}")),
                textTurn("done", 20, 7));

        LoopResult result = loop(8).run("add one and one");

        assertThat(result.rollup().totalInputTokens()).isEqualTo(30);
        assertThat(result.rollup().totalOutputTokens()).isEqualTo(12);
        assertThat(result.rollup().iterations()).isEqualTo(2);
    }

    @Test
    void sumsCacheTokensOncePerModelCallFromNativeUsage() {
        // Two model calls, each reporting native Anthropic cache tokens (creation on the first call,
        // reads on the second — the typical warm-up pattern). The rollup sums them once per model
        // call, mirroring the input/output rule (never per tool step).
        when(chatModel.call(any(Prompt.class))).thenReturn(
                toolTurnWithCache(10, 5, 1500, 0, call("calculator", "{\"expression\":\"1+1\"}")),
                textTurnWithCache("done", 20, 7, 0, 1500));

        LoopResult result = loop(8).run("add one and one");

        assertThat(result.rollup().totalInputTokens()).isEqualTo(30);
        assertThat(result.rollup().totalOutputTokens()).isEqualTo(12);
        assertThat(result.rollup().totalCacheCreationInputTokens()).isEqualTo(1500);
        assertThat(result.rollup().totalCacheReadInputTokens()).isEqualTo(1500);
        assertThat(result.rollup().iterations()).isEqualTo(2);
    }

    // --- ChatResponse builders ---------------------------------------------------------------

    private AssistantMessage.ToolCall call(String name, String argumentsJson) {
        return new AssistantMessage.ToolCall("call-1", "function", name, argumentsJson);
    }

    private ChatResponse toolTurn(String text, int in, int out, AssistantMessage.ToolCall... calls) {
        return response(AssistantMessage.builder().content(text).toolCalls(List.of(calls)).build(), in, out);
    }

    private ChatResponse textTurn(String text, int in, int out) {
        return response(new AssistantMessage(text), in, out);
    }

    private ChatResponse toolTurnWithCache(int in, int out, int cacheCreation, int cacheRead,
            AssistantMessage.ToolCall toolCall) {
        return responseWithCache(
                AssistantMessage.builder().content("calc").toolCalls(List.of(toolCall)).build(),
                in, out, cacheCreation, cacheRead);
    }

    private ChatResponse textTurnWithCache(String text, int in, int out, int cacheCreation, int cacheRead) {
        return responseWithCache(new AssistantMessage(text), in, out, cacheCreation, cacheRead);
    }

    /** A response whose provider-native usage is an Anthropic {@code Usage} carrying cache counts. */
    private ChatResponse responseWithCache(AssistantMessage message, int in, int out,
            int cacheCreation, int cacheRead) {
        AnthropicApi.Usage nativeUsage = new AnthropicApi.Usage(in, out, cacheCreation, cacheRead);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(in, out, in + out, nativeUsage))
                .build();
        return new ChatResponse(List.of(new Generation(message)), metadata);
    }

    /**
     * Mirrors Spring AI's Anthropic mapping splitting one assistant turn into an empty leading
     * {@link Generation} plus a separate Generation carrying the {@code tool_use} block.
     */
    private ChatResponse splitToolTurn(int in, int out, AssistantMessage.ToolCall call) {
        AssistantMessage empty = AssistantMessage.builder().content("").build();
        AssistantMessage withCall = AssistantMessage.builder().content("").toolCalls(List.of(call)).build();
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(in, out, in + out))
                .build();
        return new ChatResponse(List.of(new Generation(empty), new Generation(withCall)), metadata);
    }

    private ChatResponse response(AssistantMessage message, int in, int out) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(in, out, in + out))
                .build();
        return new ChatResponse(List.of(new Generation(message)), metadata);
    }
}

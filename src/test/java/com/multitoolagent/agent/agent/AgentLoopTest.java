package com.multitoolagent.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multitoolagent.agent.TestFixtures;
import com.multitoolagent.agent.agent.llm.ILlmClient;
import com.multitoolagent.agent.exception.AgentExecutionException;
import com.multitoolagent.agent.model.TraceStep;
import com.multitoolagent.agent.tool.CalculatorTool;
import com.multitoolagent.agent.tool.ITool;
import com.multitoolagent.agent.tool.ToolRegistry;
import com.multitoolagent.agent.tool.ToolResult;
import com.multitoolagent.agent.util.JsonSupport;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

/**
 * The reason&rarr;act&rarr;observe loop's behaviour tests, written <b>once</b> and run against
 * <b>every engine</b>. Each concrete subclass ({@code SpringAiAgentLoopTest},
 * {@code LangChain4jAgentLoopTest}) plugs in its real {@link ILlmClient} adapter over a mocked
 * framework model, so every scenario below exercises the real loop <em>and</em> the real adapter's
 * translation &mdash; only the network call is faked. JUnit therefore lists each scenario once per engine.
 *
 * <p>Scenarios are scripted in neutral terms ({@link #toolTurn}, {@link #textTurn}); a subclass turns
 * each {@link Turn} into its framework's response objects ({@link #script}).
 */
abstract class AgentLoopTest {

    // --- Engine hooks: the only things a subclass provides ---------------------------------------

    /** Builds the real adapter under test, wired to the subclass's mocked framework model. */
    protected abstract ILlmClient newClient(ToolRegistry registry, JsonSupport json);

    /** Makes the mocked framework model return these turns in order (the last one repeats). */
    protected abstract void script(List<Turn> turns);

    /** How many times the mocked framework model was called. */
    protected abstract int modelCalls();

    /** The ids of the tool results sent to the model on its most recent call (oldest first). */
    protected abstract List<String> toolResultIdsSentOnLastCall();

    // --- Neutral scripting --------------------------------------------------------------------

    /** A tool call the scripted model asks for. */
    protected record Call(String id, String name, String argumentsJson) {
    }

    /** One scripted model reply: text, requested tool calls, token usage and cache tokens. */
    protected record Turn(String text, List<Call> calls, int in, int out, int cacheCreation, int cacheRead) {
    }

    protected static Call call(String name, String argumentsJson) {
        return new Call("call-1", name, argumentsJson);
    }

    protected static Turn toolTurn(String text, int in, int out, Call... calls) {
        return new Turn(text, List.of(calls), in, out, 0, 0);
    }

    protected static Turn textTurn(String text, int in, int out) {
        return new Turn(text, List.of(), in, out, 0, 0);
    }

    // --- Fixture --------------------------------------------------------------------------------

    protected final SystemPromptFactory promptFactory = Mockito.mock(SystemPromptFactory.class);
    protected final ToolRegistry registry = new ToolRegistry(List.of((ITool) new CalculatorTool()));
    protected final JsonSupport json = new JsonSupport(new ObjectMapper());

    @BeforeEach
    void stubPrompt() {
        when(promptFactory.build()).thenReturn("system prompt");
    }

    private AgentLoop loop(int maxIterations) {
        return new AgentLoop(registry, promptFactory, json, TestFixtures.props(maxIterations));
    }

    private LoopResult run(int maxIterations, String task) {
        return loop(maxIterations).run(task, newClient(registry, json));
    }

    // --- Scenarios --------------------------------------------------------------------------------

    @Test
    void dispatchesToolThenReturnsFinalAnswer() {
        script(List.of(
                toolTurn("Let me calculate.", 10, 5, call("calculator", "{\"expression\":\"2 + 2 * 3\"}")),
                textTurn("The result is 8.", 20, 7)));

        LoopResult result = run(8, "what is 2 + 2 * 3");

        assertThat(result.finalAnswer()).isEqualTo("The result is 8.");
        assertThat(result.trace()).extracting(TraceStep::type)
                .containsExactly("model_call", "tool", "final_answer");

        TraceStep toolStep = result.trace().get(1);
        assertThat(toolStep.tool()).isEqualTo("calculator");
        assertThat(((ToolResult) toolStep.toolOutput()).ok()).isTrue();

        TraceStep finalStep = result.trace().get(2);
        assertThat(finalStep.type()).isEqualTo("final_answer");
        assertThat(finalStep.toolsUsed()).containsExactly("calculator");
        assertThat(modelCalls()).isEqualTo(2);
    }

    @Test
    void feedsTheToolResultBackUnderTheModelsCallId() {
        // The tool result must echo the id of the call it answers, or the model can't pair them.
        script(List.of(
                toolTurn("Calculating.", 10, 5, new Call("toolu_42", "calculator", "{\"expression\":\"1+1\"}")),
                textTurn("2", 20, 7)));

        run(8, "what is 1+1");

        assertThat(toolResultIdsSentOnLastCall()).containsExactly("toolu_42");
    }

    @Test
    void splitsReasoningFromValueWhenFinalTurnIsStructuredJson() {
        // The model returns its final turn as {"reasoning","answer"} (rule 6). The loop stores the
        // value alone as the answer and lifts the explanation into its own reasoning step.
        script(List.of(
                toolTurn("Calculating.", 10, 5, call("calculator", "{\"expression\":\"12 * 3\"}")),
                textTurn("{\"reasoning\":\"reused an earlier confirmed result\",\"answer\":\"36\"}", 20, 7)));

        LoopResult result = run(8, "what is 12 * 3");

        assertThat(result.finalAnswer()).isEqualTo("36");
        assertThat(result.trace()).extracting(TraceStep::type)
                .containsExactly("model_call", "tool", "reasoning", "final_answer");

        TraceStep reasoning = result.trace().get(2);
        assertThat(reasoning.thought()).isEqualTo("reused an earlier confirmed result");
        assertThat(reasoning.tokens().inputTokens()).isEqualTo(20);   // this turn's tokens live on the reasoning step

        TraceStep finalStep = result.trace().get(3);
        assertThat(finalStep.thought()).isEqualTo("36");
        assertThat(finalStep.toolsUsed()).containsExactly("calculator");
        assertThat(finalStep.latencyMs()).isZero();      // value step carries 0/null so tokens aren't double-counted
        assertThat(finalStep.tokens()).isNull();
    }

    @Test
    void extractsTheEnvelopeEvenWhenTheModelWrapsItInProse() {
        // The model sometimes disobeys "raw JSON only" and adds a prose preamble before the object.
        // The value must still be pulled from the embedded envelope, not stored as the whole blob.
        String wrapped = "The web search did not return the digits. However, this value was established "
                + "earlier in our conversation:\n\n"
                + "{\"reasoning\":\"established earlier in this conversation\",\"answer\":\"3.14159\"}";
        script(List.of(
                toolTurn("Searching.", 10, 5, call("calculator", "{\"expression\":\"1\"}")),
                textTurn(wrapped, 20, 7)));

        LoopResult result = run(8, "first digits of pi");

        assertThat(result.finalAnswer()).isEqualTo("3.14159");
        assertThat(result.trace()).extracting(TraceStep::type)
                .containsExactly("model_call", "tool", "reasoning", "final_answer");
        assertThat(result.trace().get(2).thought()).isEqualTo("established earlier in this conversation");
        assertThat(result.trace().get(3).thought()).isEqualTo("3.14159");
    }

    @Test
    void treatsAPlainTextFinalTurnAsTheAnswer() {
        // A final turn that is not the rule-6 JSON envelope is used verbatim as the answer, with no
        // reasoning step — preserving the pre-envelope behaviour (a bare "36" is a JSON number, not an
        // object, so it still takes this fallback).
        script(List.of(
                toolTurn("Calculating.", 10, 5, call("calculator", "{\"expression\":\"6 * 6\"}")),
                textTurn("36", 20, 7)));

        LoopResult result = run(8, "what is 6 * 6");

        assertThat(result.finalAnswer()).isEqualTo("36");
        assertThat(result.trace()).extracting(TraceStep::type)
                .containsExactly("model_call", "tool", "final_answer");
        TraceStep finalStep = result.trace().get(2);
        assertThat(finalStep.thought()).isEqualTo("36");
        assertThat(finalStep.tokens()).isNotNull();     // fallback keeps tokens/latency on the final_answer step
    }

    @Test
    void returnsUnsupportedWithReasoningStepWhenNoToolIsUsed() {
        // Scope gate: no tool used -> out of scope. The model's explanation is preserved as a
        // `reasoning` step and the verdict is a synthetic `final_answer` of exactly "Unsupported".
        String explanation = "Power conversion is out of scope; unit_converter only supports "
                + "length/weight/temperature/currency.";
        script(List.of(textTurn(explanation, 5, 3)));

        LoopResult result = run(8, "convert 1000000 watts to kilowatts");

        assertThat(result.finalAnswer()).isEqualTo("Unsupported");
        assertThat(result.trace()).extracting(TraceStep::type)
                .containsExactly("reasoning", "final_answer");

        TraceStep reasoning = result.trace().get(0);
        assertThat(reasoning.thought()).contains("out of scope").isNotEqualTo("Unsupported");

        TraceStep finalStep = result.trace().get(1);
        assertThat(finalStep.thought()).isEqualTo("Unsupported");
        assertThat(finalStep.latencyMs()).isZero();
        assertThat(finalStep.tokens()).isNull();
        assertThat(finalStep.toolsUsed()).isNull();

        assertThat(result.rollup().iterations()).isEqualTo(1);
        assertThat(modelCalls()).isEqualTo(1);
    }

    @Test
    void logsTheTaskAndScopeReasonAtWarnWhenNoToolIsUsed() {
        // Observability: a rejection must be diagnosable from the logs alone, not only the payload.
        ListAppender<ILoggingEvent> appender = attachAppenderTo(AgentLoop.class);
        String explanation = "This is a coding task, which none of the four tools can perform.";
        script(List.of(textTurn(explanation, 5, 3)));

        run(8, "write a bubble sort in Java");

        ILoggingEvent warn = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a WARN log for the out-of-scope task"));
        assertThat(warn.getFormattedMessage())
                .contains("out of scope")
                .contains("write a bubble sort in Java")
                .contains(explanation);
    }

    @Test
    void returnsSingleUnsupportedStepWhenNoToolAndNoText() {
        // No tool AND no explanatory text -> just the synthetic final_answer, no reasoning step.
        script(List.of(textTurn("", 5, 1)));

        LoopResult result = run(8, "something out of scope");

        assertThat(result.finalAnswer()).isEqualTo("Unsupported");
        assertThat(result.trace()).extracting(TraceStep::type).containsExactly("final_answer");
        assertThat(result.trace().get(0).thought()).isEqualTo("Unsupported");
        assertThat(result.trace().get(0).toolsUsed()).isNull();
        assertThat(result.rollup().iterations()).isEqualTo(1);
    }

    @Test
    void recoversWhenAToolErrorIsFedBack() {
        script(List.of(
                toolTurn("Trying a tool.", 8, 4, call("does_not_exist", "{}")),
                textTurn("Sorry, I could not complete that.", 6, 2)));

        LoopResult result = run(8, "use a missing tool");

        TraceStep toolStep = result.trace().stream()
                .filter(step -> "tool".equals(step.type())).findFirst().orElseThrow();
        ToolResult output = (ToolResult) toolStep.toolOutput();
        assertThat(output.ok()).isFalse();
        assertThat(output.error()).contains("Unknown tool");
        assertThat(result.finalAnswer()).contains("could not");
        assertThat(modelCalls()).isEqualTo(2);
    }

    @Test
    void throwsWhenMaxIterationsExhaustedInsteadOfFabricating() {
        // Always returns a tool call, so the loop can never terminate on a text turn.
        script(List.of(toolTurn("looping", 1, 1, call("calculator", "{\"expression\":\"1+1\"}"))));

        assertThatThrownBy(() -> run(2, "loop forever"))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("within 2 iterations");
    }

    @Test
    void sumsTokensOncePerIterationForTheRollup() {
        script(List.of(
                toolTurn("calc", 10, 5, call("calculator", "{\"expression\":\"1+1\"}")),
                textTurn("done", 20, 7)));

        LoopResult result = run(8, "add one and one");

        assertThat(result.rollup().totalInputTokens()).isEqualTo(30);
        assertThat(result.rollup().totalOutputTokens()).isEqualTo(12);
        assertThat(result.rollup().iterations()).isEqualTo(2);
    }

    @Test
    void sumsCacheTokensOncePerModelCallFromNativeUsage() {
        // Two model calls, each reporting native Anthropic cache tokens (creation on the first call,
        // reads on the second — the typical warm-up pattern). The rollup sums them once per model
        // call, mirroring the input/output rule (never per tool step).
        script(List.of(
                new Turn("calc", List.of(call("calculator", "{\"expression\":\"1+1\"}")), 10, 5, 1500, 0),
                new Turn("done", List.of(), 20, 7, 0, 1500)));

        LoopResult result = run(8, "add one and one");

        assertThat(result.rollup().totalInputTokens()).isEqualTo(30);
        assertThat(result.rollup().totalOutputTokens()).isEqualTo(12);
        assertThat(result.rollup().totalCacheCreationInputTokens()).isEqualTo(1500);
        assertThat(result.rollup().totalCacheReadInputTokens()).isEqualTo(1500);
        assertThat(result.rollup().iterations()).isEqualTo(2);
    }

    /** Attaches a fresh in-memory Logback appender to a class's logger so a test can read its logs. */
    private ListAppender<ILoggingEvent> attachAppenderTo(Class<?> loggingClass) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggingClass);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}

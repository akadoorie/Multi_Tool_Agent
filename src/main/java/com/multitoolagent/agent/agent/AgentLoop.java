package com.multitoolagent.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.multitoolagent.agent.agent.llm.AgentMessage;
import com.multitoolagent.agent.agent.llm.ILlmClient;
import com.multitoolagent.agent.agent.llm.ModelTurn;
import com.multitoolagent.agent.config.AppProperties;
import com.multitoolagent.agent.exception.AgentExecutionException;
import com.multitoolagent.agent.model.TaskRollup;
import com.multitoolagent.agent.model.TraceStep;
import com.multitoolagent.agent.tool.ToolRegistry;
import com.multitoolagent.agent.tool.ToolResult;
import com.multitoolagent.agent.util.JsonSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The explicit reason&rarr;act&rarr;observe loop &mdash; the heart of the agent.
 *
 * <p>Each iteration: call the model (reason); if it returned tool calls, execute them (act) and feed
 * the real results back (observe); repeat. The loop terminates when the model returns a plain text
 * turn with no tool calls. Every step is timed and recorded into the trace, and the model can only
 * ever see real tool output &mdash; it can never fabricate a tool result.
 *
 * <p>The loop is framework-free: it talks to the model only through an {@link ILlmClient}, chosen per
 * task (Spring AI or LangChain4j). Everything that defines the agent's behaviour &mdash; the scope
 * gate, the final-answer split, the trace and the token rollup &mdash; lives here once, so both
 * engines behave identically.
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /**
     * Returned when a task finishes without ever invoking a tool. Every legitimate answer must be
     * grounded in a real tool result, so a no-tool answer is by definition out of scope. This is the
     * structural backstop behind the prompt's scope rule &mdash; it holds even if the model tries to
     * answer from its own knowledge.
     */
    private static final String UNSUPPORTED = "Unsupported";

    private final ToolRegistry registry;
    private final SystemPromptFactory promptFactory;
    private final JsonSupport json;
    private final AppProperties props;

    public AgentLoop(ToolRegistry registry, SystemPromptFactory promptFactory, JsonSupport json,
            AppProperties props) {
        this.registry = registry;
        this.promptFactory = promptFactory;
        this.json = json;
        this.props = props;
    }

    /** Single-shot convenience: run a task with no prior conversation context. */
    public LoopResult run(String task, ILlmClient client) {
        return run(task, List.of(), client);
    }

    /**
     * Runs the loop for a task, seeded with prior conversation turns as context. The history is
     * appended <em>after</em> the cached system+tools prefix &mdash; oldest&rarr;newest as alternating
     * user/assistant messages &mdash; then the new user task, so the prompt-cache prefix stays
     * byte-stable. Only the task + answer of each prior turn is resent (not its trace/tool steps).
     *
     * @param client the engine that makes the model calls for this task (Spring AI or LangChain4j).
     * @return the final answer, the trace, and the rollup (this turn's work only; the resent history is
     *         naturally counted in this turn's input tokens, which is correct).
     * @throws AgentExecutionException if the iteration cap is reached without a final answer.
     */
    public LoopResult run(String task, List<PriorTurn> history, ILlmClient client) {
        long taskStartNanos = System.nanoTime();

        List<AgentMessage> conversation = new ArrayList<>();
        conversation.add(new AgentMessage.System(promptFactory.build()));
        for (PriorTurn turn : history) {
            conversation.add(new AgentMessage.User(turn.task()));
            conversation.add(AgentMessage.Assistant.text(turn.finalAnswer()));
        }
        conversation.add(new AgentMessage.User(task));

        List<TraceStep> trace = new ArrayList<>();
        int stepIndex = 0;
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        long totalCacheReadInputTokens = 0;
        long totalCacheCreationInputTokens = 0;
        int maxIterations = props.agent().maxIterations();

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            // ── REASON ──────────────────────────────────────────────────────────
            // Call the model; it reasons over the conversation and returns either a
            // final answer (no tool calls) or one or more tool-call requests.
            long modelStartNanos = System.nanoTime();
            ModelTurn turn = client.call(conversation);
            long modelLatencyMs = elapsedMs(modelStartNanos);

            TraceStep.TokenUsage usage = turn.usage();
            // Sum tokens once per model call (never per tool step) so parallel tool calls in one turn
            // are not double-counted. Cache counts follow the same once-per-call rule.
            totalInputTokens += nullToZero(usage.inputTokens());
            totalOutputTokens += nullToZero(usage.outputTokens());
            totalCacheReadInputTokens += turn.cacheReadTokens();
            totalCacheCreationInputTokens += turn.cacheCreationTokens();
            log.debug("Model call usage ({}): input={} output={} cache_read={} cache_creation={}",
                    client.id(), usage.inputTokens(), usage.outputTokens(),
                    turn.cacheReadTokens(), turn.cacheCreationTokens());

            String assistantText = turn.text();
            List<AgentMessage.ToolCall> toolCalls = turn.toolCalls();

            // Resend the full history each turn: the Messages API is stateless.
            conversation.add(new AgentMessage.Assistant(assistantText, toolCalls));

            if (toolCalls.isEmpty()) {
                // The model reasoned to a plain-text answer — no ACT/OBSERVE this turn; the task ends here.
                // Scope gate: if the whole task ran without invoking any tool, the answer is not
                // tool-grounded, so it is out of scope -> return exactly "Unsupported" regardless of
                // what the model produced. Tool failures still count as "used" (the error is a real,
                // grounded observation), so a legitimate "the tool failed" reply is preserved.
                // The distinct tools this task invoked, in first-use order — recorded on the
                // final_answer step so provenance is structured data, not text baked into the answer.
                List<String> toolsUsed = trace.stream()
                        .filter(step -> "tool".equals(step.type()))
                        .map(TraceStep::tool)
                        .distinct()
                        .toList();
                boolean anyToolUsed = !toolsUsed.isEmpty();
                String finalAnswer;
                if (anyToolUsed) {
                    // The model returns its final turn as {"reasoning","answer"} (rule 6). Split so the
                    // stored answer is the value alone and any explanation lives in its own step. A
                    // plain-text turn (no such JSON) falls back to being the answer verbatim.
                    JsonNode envelope = finalAnswerEnvelope(assistantText);
                    finalAnswer = envelope != null ? envelope.get("answer").asText() : assistantText;
                    String reasoning = envelope != null && envelope.hasNonNull("reasoning")
                            ? envelope.get("reasoning").asText() : "";
                    if (!reasoning.isBlank()) {
                        // One model turn, split for presentation (mirrors the Unsupported path): the
                        // reasoning step carries this turn's latency + tokens; the final_answer step
                        // carries 0/null so the rollup is not double-counted.
                        trace.add(TraceStep.reasoning(++stepIndex, iteration, reasoning, modelLatencyMs, usage));
                        trace.add(TraceStep.finalAnswer(++stepIndex, iteration, toolsUsed, finalAnswer, 0, null));
                        log.info("Final-answer reasoning: [\"{}\"]", oneLine(reasoning));
                    } else {
                        trace.add(TraceStep.finalAnswer(++stepIndex, iteration, toolsUsed, finalAnswer, modelLatencyMs, usage));
                    }
                    log.info("Task completed in {} iteration(s) using tools {}.", iteration, toolsUsed);
                } else if (assistantText != null && !assistantText.isBlank()) {
                    // Out of scope, but the model explained why. Keep that explanation as a `reasoning`
                    // step (with this turn's latency + tokens) so the verdict is legible, then append a
                    // synthetic `final_answer` of "Unsupported" (latency 0, no tokens — it is not a
                    // separate model call, which also avoids implying double token usage).
                    finalAnswer = UNSUPPORTED;
                    trace.add(TraceStep.reasoning(++stepIndex, iteration, assistantText, modelLatencyMs, usage));
                    trace.add(TraceStep.finalAnswer(++stepIndex, iteration, null, UNSUPPORTED, 0, null));
                    // Surface WHY the task was rejected at WARN, so operators can diagnose scope
                    // decisions from the logs alone. The same reason is in the trace's reasoning step
                    // for API consumers. Newlines are collapsed so one rejection stays one log line.
                    log.warn("Task out of scope — no tool applied. task=\"{}\" reason=\"{}\"",
                            oneLine(task), oneLine(assistantText));
                } else {
                    // Out of scope with no explanation: a single "Unsupported" verdict.
                    finalAnswer = UNSUPPORTED;
                    trace.add(TraceStep.finalAnswer(++stepIndex, iteration, null, UNSUPPORTED, modelLatencyMs, usage));
                    log.warn("Task out of scope — no tool applied and the model gave no reason. task=\"{}\"",
                            oneLine(task));
                }
                return new LoopResult(finalAnswer, List.copyOf(trace),
                        new TaskRollup(elapsedMs(taskStartNanos), totalInputTokens, totalOutputTokens,
                                iteration, totalCacheReadInputTokens, totalCacheCreationInputTokens));
            }

            // One model_call step per iteration carries the reasoning text + tokens for this turn.
            trace.add(TraceStep.modelCall(++stepIndex, iteration, assistantText, modelLatencyMs, usage));

            // ── ACT ─────────────────────────────────────────────────────────────
            // The model requested tools — execute each real tool it asked for (timed).
            List<AgentMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AgentMessage.ToolCall call : toolCalls) {
                JsonNode input = json.parseOrEmpty(call.argumentsJson());
                long toolStartNanos = System.nanoTime();
                ToolResult result = registry.execute(call.name(), input);
                long toolLatencyMs = elapsedMs(toolStartNanos);

                trace.add(TraceStep.tool(++stepIndex, iteration, call.name(), input, result, toolLatencyMs));
                // Feed the REAL tool output back to the model.
                toolResponses.add(new AgentMessage.ToolResponse(call.id(), call.name(), json.write(result)));
            }
            // ── OBSERVE ──────────────────────────────────────────────────────────
            // Feed the real tool results back into the conversation so the next
            // iteration's REASON observes them, then loop.
            conversation.add(new AgentMessage.ToolResults(toolResponses));
        }

        // Loop exhausted: fail loudly rather than fabricate an answer.
        throw new AgentExecutionException(
                "Could not complete the task within " + maxIterations + " iterations.");
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * Flattens a value for single-line logging: newlines/carriage returns become spaces so a
     * multi-line task cannot forge extra log lines (log injection). Null becomes an empty string.
     */
    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").strip();
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * Pulls the final-answer envelope ({@code {"reasoning","answer"}}, rule 6) out of the model's
     * terminating turn. The model usually returns just that object, but sometimes wraps it in a prose
     * preamble or code fences; this recognises both — first trying the whole text, then the first
     * {@code {…}} object embedded in it. Returns null when there is no such object (a plain-text
     * answer), so the caller uses the raw text unchanged. Requiring an {@code "answer"} field keeps a
     * bare value like {@code 36} (a JSON number, not an object) or unrelated braces on the raw path.
     */
    private JsonNode finalAnswerEnvelope(String assistantText) {
        if (assistantText == null) {
            return null;
        }
        JsonNode whole = json.parseOrEmpty(assistantText);
        if (whole.isObject() && whole.hasNonNull("answer")) {
            return whole;
        }
        int start = assistantText.indexOf('{');
        int end = assistantText.lastIndexOf('}');
        if (start >= 0 && end > start) {
            JsonNode embedded = json.parseOrEmpty(assistantText.substring(start, end + 1));
            if (embedded.isObject() && embedded.hasNonNull("answer")) {
                return embedded;
            }
        }
        return null;
    }
}

package com.tufin.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.tufin.agent.config.AppProperties;
import com.tufin.agent.exception.AgentExecutionException;
import com.tufin.agent.model.TaskRollup;
import com.tufin.agent.model.TraceStep;
import com.tufin.agent.tool.ToolRegistry;
import com.tufin.agent.tool.ToolResult;
import com.tufin.agent.util.JsonSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * The explicit reason&rarr;act&rarr;observe loop &mdash; the heart of the agent.
 *
 * <p>Each iteration: call the model (reason); if it returned tool calls, execute them (act) and feed
 * the real results back (observe); repeat. The loop terminates when the model returns a plain text
 * turn with no tool calls. Every step is timed and recorded into the trace, and the model can only
 * ever see real tool output &mdash; it can never fabricate a tool result.
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

    private final ChatModel chatModel;
    private final ToolRegistry registry;
    private final ChatOptionsFactory optionsFactory;
    private final SystemPromptFactory promptFactory;
    private final JsonSupport json;
    private final AppProperties props;

    public AgentLoop(ChatModel chatModel, ToolRegistry registry, ChatOptionsFactory optionsFactory,
            SystemPromptFactory promptFactory, JsonSupport json, AppProperties props) {
        this.chatModel = chatModel;
        this.registry = registry;
        this.optionsFactory = optionsFactory;
        this.promptFactory = promptFactory;
        this.json = json;
        this.props = props;
    }

    /** Anthropic-native prompt-cache token counts for one model call (0 when absent / not cached). */
    private record CacheUsage(long read, long creation) {
    }

    /** Single-shot convenience: run a task with no prior conversation context. */
    public LoopResult run(String task) {
        return run(task, List.of());
    }

    /**
     * Runs the loop for a task, seeded with prior conversation turns as context. The history is
     * appended <em>after</em> the cached system+tools prefix &mdash; oldest&rarr;newest as alternating
     * user/assistant messages &mdash; then the new user task, so the prompt-cache prefix stays
     * byte-stable. Only the task + answer of each prior turn is resent (not its trace/tool steps).
     *
     * @return the final answer, the trace, and the rollup (this turn's work only; the resent history is
     *         naturally counted in this turn's input tokens, which is correct).
     * @throws AgentExecutionException if the iteration cap is reached without a final answer.
     */
    public LoopResult run(String task, List<PriorTurn> history) {
        long taskStartNanos = System.nanoTime();

        List<Message> conversation = new ArrayList<>();
        conversation.add(new SystemMessage(promptFactory.build()));
        for (PriorTurn turn : history) {
            conversation.add(new UserMessage(turn.task()));
            conversation.add(new AssistantMessage(turn.finalAnswer()));
        }
        conversation.add(new UserMessage(task));

        ChatOptions options = optionsFactory.build();
        List<TraceStep> trace = new ArrayList<>();
        int stepIndex = 0;
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        long totalCacheReadInputTokens = 0;
        long totalCacheCreationInputTokens = 0;
        int maxIterations = props.agent().maxIterations();

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            long modelStartNanos = System.nanoTime();
            ChatResponse response = chatModel.call(new Prompt(conversation, options));
            long modelLatencyMs = elapsedMs(modelStartNanos);

            TraceStep.TokenUsage usage = extractUsage(response);
            CacheUsage cacheUsage = extractCacheUsage(response);
            // Sum tokens once per model call (never per tool step) so parallel tool calls in one turn
            // are not double-counted. Cache counts follow the same once-per-call rule.
            totalInputTokens += nullToZero(usage.inputTokens());
            totalOutputTokens += nullToZero(usage.outputTokens());
            totalCacheReadInputTokens += cacheUsage.read();
            totalCacheCreationInputTokens += cacheUsage.creation();
            log.debug("Model call usage: input={} output={} cache_read={} cache_creation={}",
                    usage.inputTokens(), usage.outputTokens(), cacheUsage.read(), cacheUsage.creation());

            // Spring AI's Anthropic mapping can split ONE assistant turn across several Generations
            // (e.g. an empty leading text block plus a separate tool_use block). Reading only
            // response.getResult() (the first Generation) would miss tool calls that landed in a
            // later one, so we aggregate text and tool calls across every Generation and rebuild a
            // single assistant turn to add to the history.
            String assistantText = aggregateText(response.getResults());
            List<AssistantMessage.ToolCall> toolCalls = aggregateToolCalls(response.getResults());
            AssistantMessage assistant = AssistantMessage.builder()
                    .content(assistantText == null ? "" : assistantText)
                    .toolCalls(toolCalls)
                    .build();

            // Resend the full history each turn: the Messages API is stateless.
            conversation.add(assistant);

            if (toolCalls.isEmpty()) {
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
                    finalAnswer = assistantText;
                    trace.add(TraceStep.finalAnswer(++stepIndex, iteration, toolsUsed, finalAnswer, modelLatencyMs, usage));
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

            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : toolCalls) {
                JsonNode input = json.parseOrEmpty(call.arguments());
                long toolStartNanos = System.nanoTime();
                ToolResult result = registry.execute(call.name(), input);
                long toolLatencyMs = elapsedMs(toolStartNanos);

                trace.add(TraceStep.tool(++stepIndex, iteration, call.name(), input, result, toolLatencyMs));
                // Feed the REAL tool output back to the model.
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        call.id(), call.name(), json.write(result)));
            }
            conversation.add(ToolResponseMessage.builder().responses(toolResponses).build());
        }

        // Loop exhausted: fail loudly rather than fabricate an answer.
        throw new AgentExecutionException(
                "Could not complete the task within " + maxIterations + " iterations.");
    }

    /** Joins the non-blank text from every Generation; returns null if the turn carried no text. */
    private static String aggregateText(List<Generation> generations) {
        String joined = generations.stream()
                .map(generation -> generation.getOutput().getText())
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n"));
        return joined.isBlank() ? null : joined;
    }

    /** Collects the tool calls from every Generation into one list (never null). */
    private static List<AssistantMessage.ToolCall> aggregateToolCalls(List<Generation> generations) {
        return generations.stream()
                .map(Generation::getOutput)
                .map(AssistantMessage::getToolCalls)
                .filter(calls -> calls != null)
                .flatMap(List::stream)
                .toList();
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

    /** Reads token usage defensively &mdash; some responses may carry no usage metadata. */
    private static TraceStep.TokenUsage extractUsage(ChatResponse response) {
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null) {
            return new TraceStep.TokenUsage(0, 0, 0);
        }
        return new TraceStep.TokenUsage(
                asInt(usage.getPromptTokens()),
                asInt(usage.getCompletionTokens()),
                asInt(usage.getTotalTokens()));
    }

    /** Accepts whatever numeric type the Spring AI {@code Usage} getters return (Integer or Long). */
    private static Integer asInt(Number value) {
        return value == null ? 0 : value.intValue();
    }

    /**
     * Reads Anthropic prompt-cache token counts from the response's provider-native usage. These are
     * not on Spring AI's generic {@link Usage}; they live only on {@link AnthropicApi.Usage}, reachable
     * via {@link Usage#getNativeUsage()}. Returns zeros when the native usage isn't Anthropic's (e.g. a
     * mocked test response) or the counts are absent &mdash; e.g. when the prefix is under the model's
     * cache minimum, Anthropic returns no cache tokens (silently uncached).
     */
    private static CacheUsage extractCacheUsage(ChatResponse response) {
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        Object nativeUsage = usage == null ? null : usage.getNativeUsage();
        if (nativeUsage instanceof AnthropicApi.Usage anthropic) {
            return new CacheUsage(nullToZero(anthropic.cacheReadInputTokens()),
                    nullToZero(anthropic.cacheCreationInputTokens()));
        }
        return new CacheUsage(0, 0);
    }
}

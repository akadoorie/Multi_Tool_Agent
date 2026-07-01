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
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /**
     * Runs the loop for a task.
     *
     * @return the final answer, the trace, and the rollup.
     * @throws AgentExecutionException if the iteration cap is reached without a final answer.
     */
    public LoopResult run(String task) {
        long taskStartNanos = System.nanoTime();

        List<Message> conversation = new ArrayList<>();
        conversation.add(new SystemMessage(promptFactory.build()));
        conversation.add(new UserMessage(task));

        ChatOptions options = optionsFactory.build();
        List<TraceStep> trace = new ArrayList<>();
        int stepIndex = 0;
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int maxIterations = props.agent().maxIterations();

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            long modelStartNanos = System.nanoTime();
            ChatResponse response = chatModel.call(new Prompt(conversation, options));
            long modelLatencyMs = elapsedMs(modelStartNanos);

            TraceStep.TokenUsage usage = extractUsage(response);
            // Sum tokens once per model call (never per tool step) so parallel tool calls in one turn
            // are not double-counted.
            totalInputTokens += nullToZero(usage.inputTokens());
            totalOutputTokens += nullToZero(usage.outputTokens());

            // Spring AI's Anthropic mapping can split ONE assistant turn across several Generations
            // (e.g. an empty leading text block plus a separate tool_use block). Reading only
            // response.getResult() (the first Generation) would miss tool calls that landed in a
            // later one, so we aggregate text and tool calls across every Generation and rebuild a
            // single assistant turn to add to the history.
            String assistantText = aggregateText(response.getResults());
            List<AssistantMessage.ToolCall> toolCalls = aggregateToolCalls(response.getResults());
            AssistantMessage assistant =
                    new AssistantMessage(assistantText == null ? "" : assistantText, Map.of(), toolCalls);

            // Resend the full history each turn: the Messages API is stateless.
            conversation.add(assistant);

            if (toolCalls.isEmpty()) {
                trace.add(TraceStep.finalAnswer(++stepIndex, iteration, assistantText,
                        modelLatencyMs, usage));
                log.info("Task completed in {} iteration(s)", iteration);
                return new LoopResult(assistantText, List.copyOf(trace),
                        new TaskRollup(elapsedMs(taskStartNanos), totalInputTokens, totalOutputTokens,
                                iteration));
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
            conversation.add(new ToolResponseMessage(toolResponses));
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
}

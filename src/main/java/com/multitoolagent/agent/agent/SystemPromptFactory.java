package com.multitoolagent.agent.agent;

import com.multitoolagent.agent.tool.ITool;
import com.multitoolagent.agent.tool.ToolRegistry;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the tool-first system prompt. The prompt is what enforces grounding: it tells the model to
 * use tools for anything factual, to treat tool output as data, and to admit when it cannot answer
 * rather than guess. It also defines scope: a task that none of the tools can address is answered
 * with exactly {@code Unsupported}. The current date/time is injected so "current"/"today" questions
 * are anchored.
 *
 * <p>The tool-agnostic policy prose (grounding, output format, scope) lives in an <b>external
 * template file</b> &mdash; {@code app.agent.system-prompt-location}, read once at startup by
 * {@code PromptConfig} &mdash; so it can be edited without recompiling. This class fills three named
 * placeholders in that template: {@code {{tool_count}}}, {@code {{tool_catalog}}} and
 * {@code {{current_date}}}.
 *
 * <p>The list of tools (and their count) is <b>generated from the {@link ToolRegistry}</b>, not
 * written into the template, so adding a tool needs no prompt edit &mdash; the same Open/Closed win
 * the registry gives the loop. Each tool's own {@code description()} is its "when to use me" text and
 * is the single source of truth for routing. The catalog is emitted in a stable (name-sorted) order
 * so the cached system+tools prefix stays byte-identical across requests (same rule as
 * {@code ChatOptionsFactory}).
 */
public class SystemPromptFactory {

    private final Clock clock;
    private final ToolRegistry registry;
    private final String template;

    public SystemPromptFactory(Clock clock, ToolRegistry registry, String template) {
        this.clock = clock;
        this.registry = registry;
        this.template = template;
    }

    public String build() {
        // Date granularity only (yyyy-MM-dd): a finer timestamp would change the cached tools+system
        // prefix on every request and defeat Anthropic prompt caching. A date still anchors
        // "today"/"now" for the weather tool, which is all this value is for.
        String now = ZonedDateTime.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE);

        // Name-sorted so the generated prefix is byte-identical across requests (cache stability).
        List<ITool> tools = registry.all().stream()
                .sorted(Comparator.comparing(ITool::name))
                .toList();
        String catalog = tools.stream()
                .map(tool -> "- `%s`: %s".formatted(tool.name(), tool.description()))
                .collect(Collectors.joining("\n"));

        // Fill the three named placeholders in the external template. Plain replace() is safe: the
        // prose carries no literal {{…}} of its own, and it sidesteps the %-escaping fragility of
        // String.format when the prompt text is edited outside Java.
        return template
                .replace("{{tool_count}}", Integer.toString(tools.size()))
                .replace("{{tool_catalog}}", catalog)
                .replace("{{current_date}}", now);
    }
}

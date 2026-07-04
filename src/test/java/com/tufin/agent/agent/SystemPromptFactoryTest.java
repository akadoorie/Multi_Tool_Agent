package com.tufin.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Verifies the system prompt carries the instructions the whole design leans on: tool-first rules,
 * an injected "current" date, and the scope rule that yields {@code Unsupported} for out-of-scope
 * tasks. A fixed {@link Clock} makes the date assertion deterministic.
 */
class SystemPromptFactoryTest {

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-07-01T12:00:00Z"), ZoneOffset.UTC);
    private final SystemPromptFactory factory = new SystemPromptFactory(fixedClock);

    @Test
    void declaresUnsupportedForTasksOutsideTheToolsScope() {
        String prompt = factory.build();

        assertThat(prompt).contains("Unsupported");
        // Out-of-scope tasks must yield a one-line reason (captured as the reasoning step), not a
        // bare token — so the verdict is legible from the response without asking the model twice.
        assertThat(prompt).contains("short sentence");
        assertThat(prompt).contains("records your sentence as the reasoning");
        assertThat(prompt).contains("the outcome as `Unsupported`");
    }

    @Test
    void injectsTheCurrentDateAndTheFourToolRules() {
        String prompt = factory.build();

        assertThat(prompt).contains("2026-07-01");
        assertThat(prompt).contains("calculator", "weather", "unit_converter", "web_search");
    }

    @Test
    void keepsTheInjectedDateTimeInternalSoBareTimeQuestionsAreOutOfScope() {
        String prompt = factory.build();

        // The timestamp is injected to anchor "now"/"today" for tools, not as a clock to expose;
        // a bare date/time question has no supporting tool and must be Unsupported.
        assertThat(prompt).contains("date, time, or clock");
    }

    @Test
    void routesFactualQuestionsToWebSearchInsteadOfRefusing() {
        String prompt = factory.build();

        // A "formula for the area of a circle" style question is encyclopedic and must go to
        // web_search, not be refused as Unsupported just because the model already knows the answer.
        assertThat(prompt).contains("formula");
        assertThat(prompt).contains("already KNOW the answer is NOT a reason");
    }

    @Test
    void limitsUnitConverterToItsFourCategoriesAndFlagsOtherConversionsOutOfScope() {
        String prompt = factory.build();

        assertThat(prompt).contains("length", "weight", "temperature", "currency");
        assertThat(prompt).containsIgnoringCase("out of scope");
    }

    @Test
    void requiresAPlainMinimalFinalAnswer() {
        String prompt = factory.build();

        // The final answer must be plain and unembellished: no Markdown, no emojis, no filler.
        assertThat(prompt).contains("Plain text ONLY", "No emojis");
        // Provenance lives in the trace (the final step's tools_used), not in the answer text.
        assertThat(prompt).contains("Do NOT name the tools");
    }
}

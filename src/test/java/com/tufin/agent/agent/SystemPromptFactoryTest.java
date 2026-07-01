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
        // The rule must be an explicit, exact-word instruction, not a vague suggestion.
        assertThat(prompt).contains("exactly the single word `Unsupported`");
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
}

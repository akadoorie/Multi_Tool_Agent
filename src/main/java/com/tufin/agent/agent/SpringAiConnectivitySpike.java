package com.tufin.agent.agent;

import com.tufin.agent.model.TraceStep;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A live end-to-end smoke test of the whole reason&rarr;act&rarr;observe path. It is disabled by
 * default and only runs under the {@code spike} profile (a real {@code ANTHROPIC_API_KEY} is
 * required):
 *
 * <pre>ANTHROPIC_API_KEY=... mvn spring-boot:run -Dspring-boot.run.profiles=spike</pre>
 *
 * <p>Unit tests mock the {@link org.springframework.ai.chat.model.ChatModel}, so they prove our
 * orchestration but never exercise the real Spring AI &harr; Anthropic tool-calling wire format. This
 * spike closes that gap: it runs a real task through the real {@link AgentLoop} and asserts the loop
 * actually invoked a tool and produced a grounded, non-empty answer. It is what caught the Spring AI
 * Anthropic quirk where one assistant turn is split across several {@code Generation}s (see
 * {@link AgentLoop} for how the loop aggregates them).
 */
@Component
@Profile("spike")
public class SpringAiConnectivitySpike implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SpringAiConnectivitySpike.class);

    private final AgentLoop agentLoop;

    public SpringAiConnectivitySpike(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    @Override
    public void run(String... args) {
        String task = "What is 17 multiplied by 23?";
        log.info("SPIKE running a real task end-to-end: {}", task);

        LoopResult result = agentLoop.run(task);

        List<String> stepTypes = result.trace().stream().map(TraceStep::type).toList();
        log.info("SPIKE trace steps: {}", stepTypes);
        log.info("SPIKE final answer: {}", result.finalAnswer());

        boolean usedTool = result.trace().stream().anyMatch(step -> "tool".equals(step.type()));
        boolean hasAnswer = result.finalAnswer() != null && !result.finalAnswer().isBlank();
        if (usedTool && hasAnswer) {
            log.info("SPIKE PASSED: the loop invoked a real tool and produced a grounded answer.");
        } else {
            log.warn("SPIKE FAILED: expected a tool invocation and a non-empty final answer "
                    + "(usedTool={}, hasAnswer={}).", usedTool, hasAnswer);
        }
    }
}

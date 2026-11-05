package com.multitoolagent.agent.agent;

import com.multitoolagent.agent.agent.llm.ILlmClient;
import com.multitoolagent.agent.agent.llm.LlmClientRegistry;
import com.multitoolagent.agent.model.TraceStep;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A live end-to-end smoke test of the whole reason&rarr;act&rarr;observe path, run once per engine
 * (Spring AI and LangChain4j). It is disabled by default and only runs under the {@code spike} profile
 * (a real {@code ANTHROPIC_API_KEY} is required):
 *
 * <pre>ANTHROPIC_API_KEY=... mvn spring-boot:run -Dspring-boot.run.profiles=spike</pre>
 *
 * <p>Unit tests mock each framework's model, so they prove our orchestration and mapping but never
 * exercise the real framework &harr; Anthropic tool-calling wire format. This spike closes that gap: it
 * runs a real task through the real {@link AgentLoop} with each client and checks the loop actually
 * invoked a tool and produced a grounded, non-empty answer. It is what caught the Spring AI Anthropic
 * quirk where one assistant turn is split across several {@code Generation}s (handled in
 * {@code SpringAiLlmClient}).
 */
@Component
@Profile("spike")
public class LlmConnectivitySpike implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LlmConnectivitySpike.class);

    private final AgentLoop agentLoop;
    private final LlmClientRegistry llmClients;

    public LlmConnectivitySpike(AgentLoop agentLoop, LlmClientRegistry llmClients) {
        this.agentLoop = agentLoop;
        this.llmClients = llmClients;
    }

    @Override
    public void run(String... args) {
        String task = "What is 17 multiplied by 23?";
        for (ILlmClient client : llmClients.all()) {
            log.info("SPIKE [{}] running a real task end-to-end: {}", client.id(), task);

            LoopResult result = agentLoop.run(task, client);

            List<String> stepTypes = result.trace().stream().map(TraceStep::type).toList();
            log.info("SPIKE [{}] trace steps: {}", client.id(), stepTypes);
            log.info("SPIKE [{}] final answer: {}", client.id(), result.finalAnswer());

            boolean usedTool = result.trace().stream().anyMatch(step -> "tool".equals(step.type()));
            boolean hasAnswer = result.finalAnswer() != null && !result.finalAnswer().isBlank();
            if (usedTool && hasAnswer) {
                log.info("SPIKE [{}] PASSED: the loop invoked a real tool and produced a grounded answer.",
                        client.id());
            } else {
                log.warn("SPIKE [{}] FAILED: expected a tool invocation and a non-empty final answer "
                        + "(usedTool={}, hasAnswer={}).", client.id(), usedTool, hasAnswer);
            }
        }
    }
}

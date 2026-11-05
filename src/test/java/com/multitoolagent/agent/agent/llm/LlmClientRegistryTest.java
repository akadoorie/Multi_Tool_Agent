package com.multitoolagent.agent.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.multitoolagent.agent.TestFixtures;
import com.multitoolagent.agent.exception.InvalidRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Engine selection: explicit id, default fallback, unknown id, and startup misconfiguration. */
class LlmClientRegistryTest {

    private final ILlmClient springAi = client("spring-ai");
    private final ILlmClient langChain4j = client("langchain4j");

    private static ILlmClient client(String id) {
        ILlmClient client = mock(ILlmClient.class);
        when(client.id()).thenReturn(id);
        return client;
    }

    /** TestFixtures configures the default engine as spring-ai. */
    private LlmClientRegistry registry(ILlmClient... clients) {
        return new LlmClientRegistry(List.of(clients), TestFixtures.props(8));
    }

    @Test
    void resolvesTheRequestedEngine() {
        assertThat(registry(springAi, langChain4j).resolve("langchain4j")).isSameAs(langChain4j);
    }

    @Test
    void fallsBackToTheDefaultWhenNoEngineIsNamed() {
        LlmClientRegistry registry = registry(springAi, langChain4j);
        assertThat(registry.resolve(null)).isSameAs(springAi);
        assertThat(registry.resolve("  ")).isSameAs(springAi);
        assertThat(registry.defaultClient()).isSameAs(springAi);
    }

    @Test
    void rejectsAnUnknownEngineWithTheValidOptions() {
        assertThatThrownBy(() -> registry(springAi, langChain4j).resolve("openai"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("openai")
                .hasMessageContaining("[langchain4j, spring-ai]");
    }

    @Test
    void failsAtStartupWhenTheDefaultEngineIsMissing() {
        assertThatThrownBy(() -> registry(langChain4j))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-engine");
    }

    @Test
    void failsAtStartupWhenTwoClientsShareAnId() {
        assertThatThrownBy(() -> registry(springAi, client("spring-ai")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("share the id");
    }
}

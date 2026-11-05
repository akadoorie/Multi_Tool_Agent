package com.multitoolagent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.multitoolagent.agent.TestFixtures;
import com.multitoolagent.agent.agent.llm.ILlmClient;
import com.multitoolagent.agent.agent.llm.LlmClientRegistry;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Swagger drop-down for {@code ?engine=} is filled from the registry, with the default pre-selected. */
class EngineParameterCustomizerTest {

    private static ILlmClient client(String id) {
        ILlmClient client = mock(ILlmClient.class);
        when(client.id()).thenReturn(id);
        return client;
    }

    @Test
    void listsTheRegisteredEnginesAndPreselectsTheDefault() {
        // TestFixtures configures spring-ai as the default engine.
        LlmClientRegistry registry = new LlmClientRegistry(
                List.of(client("spring-ai"), client("langchain4j")), TestFixtures.props(8));
        Parameter engine = new Parameter().name("engine").in("query").schema(new StringSchema());
        Parameter page = new Parameter().name("page").in("query").schema(new IntegerSchema());
        OpenAPI openApi = new OpenAPI().paths(new Paths().addPathItem("/task",
                new PathItem().post(new Operation().addParametersItem(engine).addParametersItem(page))));

        new EngineParameterCustomizer(registry).customise(openApi);

        assertThat(engine.getSchema().getEnum()).containsExactly("langchain4j", "spring-ai");
        assertThat(engine.getSchema().getDefault()).isEqualTo("spring-ai");
        assertThat(page.getSchema().getEnum()).isNull();   // other parameters are left alone
    }
}

package com.multitoolagent.agent.config;

import com.multitoolagent.agent.agent.llm.LlmClientRegistry;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * Turns the {@code ?engine=} query parameter into a drop-down in Swagger UI.
 *
 * <p>Swagger UI renders a drop-down for a parameter whose schema lists its allowed values ({@code enum}).
 * Rather than hard-coding those values (or using a Java enum), we fill them in from
 * {@link LlmClientRegistry} when the OpenAPI document is built. So the list always matches the engines
 * that actually exist, a new {@code ILlmClient} bean shows up automatically, and the pre-selected value
 * is whatever {@code app.agent.default-engine} says.
 *
 * <p>This only affects the documentation. A request from curl with an unknown engine is still rejected
 * with 400 by {@link LlmClientRegistry#resolve}.
 */
@Component
public class EngineParameterCustomizer implements OpenApiCustomizer {

    /** Query-parameter name, shared with the controllers so the two can't drift apart. */
    public static final String PARAM = "engine";

    public static final String DESCRIPTION = "LLM engine that runs this task. "
            + "Omit to use the default (app.agent.default-engine). Unknown values are rejected with 400.";

    private final LlmClientRegistry registry;

    public EngineParameterCustomizer(LlmClientRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
            if (operation.getParameters() == null) {
                return;
            }
            for (Parameter parameter : operation.getParameters()) {
                if (PARAM.equals(parameter.getName()) && "query".equals(parameter.getIn())) {
                    StringSchema schema = new StringSchema();
                    schema.setEnum(registry.ids());
                    schema.setDefault(registry.defaultClient().id());
                    parameter.setSchema(schema);
                }
            }
        }));
    }
}

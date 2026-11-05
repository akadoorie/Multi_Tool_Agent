package com.multitoolagent.agent.agent.llm.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.multitoolagent.agent.tool.ITool;
import com.multitoolagent.agent.tool.ToolRegistry;
import com.multitoolagent.agent.util.JsonSupport;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Describes our tools to LangChain4j as {@link ToolSpecification}s (name, description, input schema),
 * so the model knows what it may call. LangChain4j never runs them &mdash; {@code AgentLoop} does.
 *
 * <p>Each {@link ITool} already publishes its input as a JSON Schema string. LangChain4j wants an
 * object schema, so we keep the top level ({@code properties} + {@code required}) and pass each
 * property's schema through unchanged as a {@link JsonRawSchema} &mdash; no re-modelling of types,
 * enums or descriptions, so both engines advertise exactly the same schema.
 *
 * <p>Specs are built once and name-sorted so the cached tools prefix is byte-identical across requests
 * (same rule as the Spring AI side).
 */
@Component
public class LangChain4jToolSpecs {

    private final List<ToolSpecification> specifications;

    public LangChain4jToolSpecs(ToolRegistry registry, JsonSupport json) {
        this.specifications = registry.all().stream()
                .sorted(Comparator.comparing(ITool::name))
                .map(tool -> toSpecification(tool, json))
                .toList();
    }

    public List<ToolSpecification> all() {
        return specifications;
    }

    private static ToolSpecification toSpecification(ITool tool, JsonSupport json) {
        return ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(toObjectSchema(json.parseOrEmpty(tool.inputSchema())))
                .build();
    }

    private static JsonObjectSchema toObjectSchema(JsonNode schema) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        JsonNode properties = schema.path("properties");
        for (Map.Entry<String, JsonNode> property : properties.properties()) {
            builder.addProperty(property.getKey(), JsonRawSchema.from(property.getValue().toString()));
        }
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(name -> required.add(name.asText()));
        return builder.required(required).build();
    }
}

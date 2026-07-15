package com.multitoolagent.agent.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Thin, testable wrapper around the application's Jackson {@link ObjectMapper} for the two JSON
 * operations the agent loop needs: parsing the model's tool-call arguments (a JSON string) into a
 * tree, and serialising a tool result back into a string to feed to the model.
 */
@Component
public class JsonSupport {

    private final ObjectMapper mapper;

    public JsonSupport(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Parses a JSON object string into a {@link JsonNode}. Returns an empty object node for null,
     * blank, or malformed input so a bad tool-argument payload becomes a recoverable validation
     * error inside a tool rather than an exception that aborts the request.
     */
    public JsonNode parseOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }

    /** Serialises any value to a JSON string, wrapping the checked Jackson exception. */
    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise value to JSON", e);
        }
    }
}

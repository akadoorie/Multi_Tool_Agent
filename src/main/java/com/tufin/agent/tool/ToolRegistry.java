package com.tufin.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves and executes tools by name. Built from every {@link ITool} bean Spring finds, so the set
 * of tools is discovered automatically at startup.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ITool> byName;

    public ToolRegistry(List<ITool> tools) {
        Map<String, ITool> map = new LinkedHashMap<>();
        for (ITool tool : tools) {
            map.put(tool.name(), tool);
        }
        this.byName = Map.copyOf(map);
        log.info("Registered {} tools: {}", byName.size(), byName.keySet());
    }

    /** All registered tools (used to advertise schemas to the model). */
    public Collection<ITool> all() {
        return byName.values();
    }

    /**
     * Executes the named tool. An unknown tool name or an unexpected exception is converted into a
     * {@link ToolResult#error} so a bad model request is fed back into the loop instead of crashing
     * the whole request.
     */
    public ToolResult execute(String name, JsonNode input) {
        ITool tool = byName.get(name);
        if (tool == null) {
            return ToolResult.error("Unknown tool '" + name + "'. Available tools: " + byName.keySet());
        }
        try {
            return tool.execute(input);
        } catch (RuntimeException e) {
            log.warn("Tool '{}' threw an unexpected exception", name, e);
            return ToolResult.error("Tool '" + name + "' failed: " + e.getMessage());
        }
    }
}

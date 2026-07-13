package com.tufin.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.springframework.stereotype.Component;

/**
 * Evaluates arithmetic expressions with a real math parser (exp4j). We deliberately never use
 * {@code eval} or a script engine: exp4j has no code-execution surface, and routing all arithmetic
 * here means math is computed deterministically instead of being guessed by the model.
 */
@Component
public class CalculatorTool implements ITool {

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "Evaluate an arithmetic expression. Use this for ALL math; never compute in your head. "
                + "Supports + - * / ^, parentheses, and functions like sqrt, sin, cos, log. "
                + "Input: {\"expression\": \"2 + 2 * 3\"}.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "expression": {
                      "type": "string",
                      "description": "The arithmetic expression to evaluate, e.g. '15 * 0.2'."
                    }
                  },
                  "required": ["expression"]
                }""";
    }

    @Override
    public ToolResult execute(JsonNode input) {
        if (!input.hasNonNull("expression")) {
            return ToolResult.error("Missing required field 'expression'.");
        }
        String expression = input.get("expression").asText();
        try {
            double result = new ExpressionBuilder(expression).build().evaluate();
            if (!Double.isFinite(result)) {
                return ToolResult.error("Expression '" + expression + "' does not evaluate to a finite number.");
            }
            return ToolResult.ok(Map.of("expression", expression, "result", result));
        } catch (RuntimeException e) {
            return ToolResult.error("Invalid expression '" + expression + "': " + e.getMessage());
        }
    }
}

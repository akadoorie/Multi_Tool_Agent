package com.multitoolagent.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode input(String expression) {
        return mapper.valueToTree(Map.of("expression", expression));
    }

    @Test
    void evaluatesArithmeticWithPrecedence() {
        ToolResult result = tool.execute(input("2 + 2 * 3"));

        assertThat(result.ok()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat((double) data.get("result")).isEqualTo(8.0);
    }

    @Test
    void returnsStructuredErrorForInvalidExpression() {
        ToolResult result = tool.execute(input("2 +"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("Invalid expression");
    }

    @Test
    void handlesDivisionByZeroGracefully() {
        // exp4j throws ArithmeticException for division by zero; the tool turns it into a
        // structured error rather than letting it escape.
        ToolResult result = tool.execute(input("1 / 0"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("Division by zero");
    }

    @Test
    void rejectsNonFiniteResult() {
        // sqrt(-1) evaluates to NaN (no exception), which the finite-value guard rejects.
        ToolResult result = tool.execute(input("sqrt(-1)"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("finite");
    }
}

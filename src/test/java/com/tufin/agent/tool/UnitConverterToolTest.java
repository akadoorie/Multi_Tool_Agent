package com.tufin.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tufin.agent.TestFixtures;
import com.tufin.agent.util.HttpJsonClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UnitConverterToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpJsonClient http = mock(HttpJsonClient.class);
    private final UnitConverterTool tool = new UnitConverterTool(TestFixtures.props(8), http);

    private JsonNode input(String category, double value, String from, String to) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("category", category);
        map.put("value", value);
        map.put("from", from);
        map.put("to", to);
        return mapper.valueToTree(map);
    }

    @Test
    void convertsCelsiusToFahrenheit() {
        ToolResult result = tool.execute(input("temperature", 100, "C", "F"));

        assertThat(result.ok()).isTrue();
        assertThat(resultValue(result)).isEqualTo(212.0);
    }

    @Test
    void convertsKilometresToMetres() {
        ToolResult result = tool.execute(input("length", 1, "km", "m"));

        assertThat(result.ok()).isTrue();
        assertThat(resultValue(result)).isEqualTo(1000.0);
    }

    @Test
    void convertsCurrencyUsingLiveRate() throws Exception {
        JsonNode frankfurterResponse = mapper.readTree(
                "{\"amount\":10.0,\"base\":\"USD\",\"date\":\"2026-07-01\",\"rates\":{\"EUR\":9.2}}");
        when(http.getJson(anyString(), anyMap())).thenReturn(frankfurterResponse);

        ToolResult result = tool.execute(input("currency", 10, "USD", "EUR"));

        assertThat(result.ok()).isTrue();
        assertThat(resultValue(result)).isEqualTo(9.2, within(1e-9));
    }

    private double resultValue(ToolResult result) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        return ((Number) data.get("result")).doubleValue();
    }
}

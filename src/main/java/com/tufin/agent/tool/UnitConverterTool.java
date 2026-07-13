package com.tufin.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.tufin.agent.config.AppProperties;
import com.tufin.agent.util.HttpJsonClient;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Converts between units of length, weight, and temperature using authoritative local factors, and
 * between currencies using the keyless Frankfurter live-rate API. Rates and factors are never guessed
 * by the model &mdash; length/weight/temperature are exact constants, and currency is a live lookup.
 */
@Component
public class UnitConverterTool implements ITool {

    /** Length units expressed in metres. */
    private static final Map<String, Double> LENGTH_IN_METRES = Map.of(
            "mm", 0.001, "cm", 0.01, "m", 1.0, "km", 1000.0,
            "in", 0.0254, "ft", 0.3048, "yd", 0.9144, "mi", 1609.344);

    /** Weight units expressed in grams. */
    private static final Map<String, Double> WEIGHT_IN_GRAMS = Map.of(
            "mg", 0.001, "g", 1.0, "kg", 1000.0, "t", 1_000_000.0,
            "oz", 28.349523125, "lb", 453.59237);

    private final AppProperties props;
    private final HttpJsonClient http;

    public UnitConverterTool(AppProperties props, HttpJsonClient http) {
        this.props = props;
        this.http = http;
    }

    @Override
    public String name() {
        return "unit_converter";
    }

    @Override
    public String description() {
        return "Convert a value between units. Categories: 'length' (mm, cm, m, km, in, ft, yd, mi), "
                + "'weight' (mg, g, kg, t, oz, lb), 'temperature' (C, F, K), and 'currency' "
                + "(ISO codes like USD, EUR, ILS via live rates). Only these categories are supported; "
                + "conversions in any other dimension (e.g. speed, data size, time, power, energy, "
                + "pressure) are NOT supported. "
                + "Input: {\"category\":\"temperature\", \"value\":100, \"from\":\"C\", \"to\":\"F\"}.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "category": {"type": "string", "enum": ["length", "weight", "temperature", "currency"]},
                    "value":    {"type": "number"},
                    "from":     {"type": "string", "description": "Source unit or ISO currency code."},
                    "to":       {"type": "string", "description": "Target unit or ISO currency code."}
                  },
                  "required": ["category", "value", "from", "to"]
                }""";
    }

    @Override
    public ToolResult execute(JsonNode input) {
        if (!input.hasNonNull("category") || !input.hasNonNull("value")
                || !input.hasNonNull("from") || !input.hasNonNull("to")) {
            return ToolResult.error("Requires fields: category, value, from, to.");
        }
        String category = input.get("category").asText().toLowerCase(Locale.ROOT);
        double value = input.get("value").asDouble();
        String from = input.get("from").asText();
        String to = input.get("to").asText();

        return switch (category) {
            case "length" -> convertByFactor("length", value, from, to, LENGTH_IN_METRES);
            case "weight" -> convertByFactor("weight", value, from, to, WEIGHT_IN_GRAMS);
            case "temperature" -> convertTemperature(value, from, to);
            case "currency" -> convertCurrency(value, from, to);
            default -> ToolResult.error("Unknown category '" + category
                    + "'. Use length, weight, temperature, or currency.");
        };
    }

    private ToolResult convertByFactor(String category, double value, String from, String to,
            Map<String, Double> factors) {
        String f = from.toLowerCase(Locale.ROOT);
        String t = to.toLowerCase(Locale.ROOT);
        Double fromFactor = factors.get(f);
        Double toFactor = factors.get(t);
        if (fromFactor == null || toFactor == null) {
            return ToolResult.error("Unsupported " + category + " unit. Supported: " + factors.keySet());
        }
        double result = value * fromFactor / toFactor;
        return ToolResult.ok(Map.of("value", value, "from", f, "to", t, "result", result));
    }

    private ToolResult convertTemperature(double value, String from, String to) {
        String f = from.toUpperCase(Locale.ROOT);
        String t = to.toUpperCase(Locale.ROOT);
        Double celsius = switch (f) {
            case "C" -> value;
            case "F" -> (value - 32) * 5.0 / 9.0;
            case "K" -> value - 273.15;
            default -> null;
        };
        if (celsius == null) {
            return ToolResult.error("Unsupported temperature unit '" + from + "'. Use C, F, or K.");
        }
        Double result = switch (t) {
            case "C" -> celsius;
            case "F" -> celsius * 9.0 / 5.0 + 32;
            case "K" -> celsius + 273.15;
            default -> null;
        };
        if (result == null) {
            return ToolResult.error("Unsupported temperature unit '" + to + "'. Use C, F, or K.");
        }
        return ToolResult.ok(Map.of("value", value, "from", f, "to", t, "result", result));
    }

    private ToolResult convertCurrency(double value, String from, String to) {
        String f = from.toUpperCase(Locale.ROOT);
        String t = to.toUpperCase(Locale.ROOT);
        String template = props.tools().currency().url() + "/latest?amount={amount}&from={from}&to={to}";
        JsonNode body = http.getJson(template, Map.of("amount", value, "from", f, "to", t));
        if (body == null || !body.has("rates") || !body.get("rates").hasNonNull(t)) {
            return ToolResult.error("Could not fetch a live rate for " + f + "->" + t
                    + " (are both valid ISO currency codes?).");
        }
        double result = body.get("rates").get(t).asDouble();
        return ToolResult.ok(Map.of("value", value, "from", f, "to", t, "result", result,
                "source", "frankfurter.app"));
    }
}

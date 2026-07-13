package com.tufin.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.tufin.agent.config.AppProperties;
import com.tufin.agent.util.HttpJsonClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Returns current weather for a city via the keyless Open-Meteo API. Two steps: geocode the city
 * name to coordinates, then fetch current conditions for those coordinates.
 */
@Component
public class WeatherTool implements ITool {

    private final AppProperties props;
    private final HttpJsonClient http;

    public WeatherTool(AppProperties props, HttpJsonClient http) {
        this.props = props;
        this.http = http;
    }

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public String description() {
        return "Get the CURRENT weather for a city (temperature, humidity, wind). "
                + "Use this for any question about current or live weather. "
                + "Input: {\"city\": \"Tel Aviv\"}.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "city": {"type": "string", "description": "City name, e.g. 'London'."}
                  },
                  "required": ["city"]
                }""";
    }

    @Override
    public ToolResult execute(JsonNode input) {
        if (!input.hasNonNull("city")) {
            return ToolResult.error("Missing required field 'city'.");
        }
        String city = input.get("city").asText();

        JsonNode geo = http.getJson(
                props.tools().weather().geocodeUrl() + "?name={name}&count=1",
                Map.of("name", city));
        if (geo == null || !geo.hasNonNull("results") || geo.get("results").isEmpty()) {
            return ToolResult.error("City not found: '" + city + "'.");
        }
        JsonNode place = geo.get("results").get(0);
        double lat = place.get("latitude").asDouble();
        double lon = place.get("longitude").asDouble();

        JsonNode forecast = http.getJson(
                props.tools().weather().forecastUrl()
                        + "?latitude={lat}&longitude={lon}"
                        + "&current=temperature_2m,relative_humidity_2m,wind_speed_10m&timezone=auto",
                Map.of("lat", lat, "lon", lon));
        if (forecast == null || !forecast.hasNonNull("current")) {
            return ToolResult.error("Weather data unavailable for '" + city + "'.");
        }
        JsonNode current = forecast.get("current");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("city", place.path("name").asText(city));
        data.put("country", place.path("country").asText(""));
        data.put("temperature_c", current.path("temperature_2m").asDouble());
        data.put("relative_humidity_pct", current.path("relative_humidity_2m").asInt());
        data.put("wind_speed_kmh", current.path("wind_speed_10m").asDouble());
        data.put("observed_at", current.path("time").asText(""));
        data.put("source", "open-meteo.com");
        return ToolResult.ok(data);
    }
}

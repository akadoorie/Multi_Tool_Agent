package com.tufin.agent;

import com.tufin.agent.config.AppProperties;
import java.time.Duration;

/** Shared builders for tests so each test doesn't repeat the full {@link AppProperties} tree. */
public final class TestFixtures {

    private TestFixtures() {
    }

    /** Full properties with the real tool URLs; {@code maxIterations} is the only knob tests vary. */
    public static AppProperties props(int maxIterations) {
        return new AppProperties(
                new AppProperties.Agent("claude-sonnet-4-6", maxIterations, 8192, 0.0, true),
                new AppProperties.Http(Duration.ofSeconds(3), Duration.ofSeconds(10)),
                new AppProperties.Tools(
                        new AppProperties.Tools.Weather(
                                "https://geocoding-api.open-meteo.com/v1/search",
                                "https://api.open-meteo.com/v1/forecast"),
                        new AppProperties.Tools.Search(
                                "https://api.duckduckgo.com/",
                                "https://en.wikipedia.org/w/rest.php/v1/search/page",
                                "https://en.wikipedia.org/api/rest_v1/page/summary/"),
                        new AppProperties.Tools.Currency("https://api.frankfurter.app")),
                new AppProperties.Persistence("./data/tasks.jsonl"));
    }
}

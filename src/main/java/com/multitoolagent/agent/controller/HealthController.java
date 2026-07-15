package com.multitoolagent.agent.controller;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal liveness endpoint. Kept hand-rolled so the path is exactly {@code /health}. */
@RestController
public class HealthController {

    @Operation(summary = "Liveness check")
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}

package com.hivesight.controller;

import com.hivesight.service.ChargebeeService;
import com.hivesight.engine.Simulator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ApiController {

    private final ChargebeeService chargebeeService;

    public ApiController(ChargebeeService chargebeeService) {
        this.chargebeeService = chargebeeService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "hivesight");
    }

    @GetMapping("/subscriptions")
    public ResponseEntity<?> listSubscriptions(
            @RequestParam(required = false) Boolean has_scheduled_changes) {
        try {
            List<Map<String, Object>> subs = chargebeeService.listSubscriptions(
                    Boolean.TRUE.equals(has_scheduled_changes));
            return ResponseEntity.ok(Map.of("subscriptions", subs));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to fetch subscriptions",
                    "message", e.getMessage()));
        }
    }

    @GetMapping("/simulate/{subscriptionId}")
    public ResponseEntity<?> simulate(
            @PathVariable String subscriptionId,
            @RequestParam(defaultValue = "18") int months) {
        try {
            Simulator.SimulationResult result = chargebeeService.simulate(subscriptionId, months);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Simulation failed",
                    "message", e.getMessage()));
        }
    }

    @GetMapping("/validate/ghost-of-march/{subscriptionId}")
    public ResponseEntity<?> validateGhostOfMarch(
            @PathVariable String subscriptionId,
            @RequestParam(required = false) String expected_cancel) {
        try {
            Simulator.SimulationResult result = chargebeeService.validateGhostOfMarch(
                    subscriptionId, expected_cancel);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Validation failed",
                    "message", e.getMessage()));
        }
    }
}

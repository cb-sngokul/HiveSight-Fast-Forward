package com.hivesight.controller;

import com.hivesight.service.ChargebeeService;
import com.hivesight.service.ChargebeeConfigService;
import com.hivesight.engine.Simulator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ApiController {

    private final ChargebeeService chargebeeService;
    private final ChargebeeConfigService configService;
    private final String defaultTimezone;

    public ApiController(ChargebeeService chargebeeService,
            ChargebeeConfigService configService,
            @Value("${chargebee.timezone:Asia/Kolkata}") String defaultTimezone) {
        this.chargebeeService = chargebeeService;
        this.configService = configService;
        this.defaultTimezone = defaultTimezone;
    }

    private ResponseEntity<?> requireChargebeeConfig() {
        if (!configService.hasRuntimeConfig()) {
            return ResponseEntity.status(400).body(Map.of(
                    "error", "Chargebee not configured",
                    "message", "Please sign in at /login.html and enter your Chargebee site and API key."));
        }
        return null;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "hivesight");
    }

    @GetMapping("/subscriptions")
    public ResponseEntity<?> listSubscriptions(
            @RequestParam(required = false) Boolean has_scheduled_changes) {
        ResponseEntity<?> configErr = requireChargebeeConfig();
        if (configErr != null) return configErr;
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
            @RequestParam(required = false) String start_month,
            @RequestParam(required = false) String end_month,
            @RequestParam(required = false) String timezone) {
        ResponseEntity<?> configErr = requireChargebeeConfig();
        if (configErr != null) return configErr;
        try {
            ZoneId zone = parseTimezone(timezone);
            long simulationStart = start_month != null && !start_month.isBlank()
                    ? Simulator.parseStartOfMonth(start_month.trim(), zone)
                    : Instant.now().atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toEpochSecond();
            long simulationEnd = end_month != null && !end_month.isBlank()
                    ? Simulator.parseEndOfMonth(end_month.trim(), zone)
                    : YearMonth.now(zone).plusMonths(18).atEndOfMonth().atTime(23, 59, 59).atZone(zone).toEpochSecond();
            Simulator.SimulationResult result = chargebeeService.simulate(subscriptionId, simulationStart, simulationEnd);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Simulation failed",
                    "message", e.getMessage()));
        }
    }

    @GetMapping("/subscription/{subscriptionId}/details")
    public ResponseEntity<?> subscriptionDetails(@PathVariable String subscriptionId) {
        ResponseEntity<?> configErr = requireChargebeeConfig();
        if (configErr != null) return configErr;
        try {
            return ResponseEntity.ok(chargebeeService.getSubscriptionDetails(subscriptionId));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to fetch subscription details",
                    "message", e.getMessage()));
        }
    }

    @GetMapping("/subscription/{subscriptionId}/debug")
    public ResponseEntity<?> subscriptionDebug(@PathVariable String subscriptionId) {
        ResponseEntity<?> configErr = requireChargebeeConfig();
        if (configErr != null) return configErr;
        try {
            Map<String, Object> sub = chargebeeService.getSubscription(subscriptionId);
            // Extract key fields that affect cancellation logic
            Map<String, Object> debug = new java.util.LinkedHashMap<>();
            debug.put("id", sub.get("id"));
            debug.put("status", sub.get("status"));
            debug.put("cancelled_at", sub.get("cancelled_at"));
            debug.put("remaining_billing_cycles", sub.get("remaining_billing_cycles"));
            debug.put("current_term_start", sub.get("current_term_start"));
            debug.put("current_term_end", sub.get("current_term_end"));
            debug.put("next_billing_at", sub.get("next_billing_at"));
            debug.put("contract_term", sub.get("contract_term"));
            debug.put("has_scheduled_changes", sub.get("has_scheduled_changes"));
            debug.put("subscription_end_date", Simulator.subscriptionEndDate(sub));
            return ResponseEntity.ok(debug);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to fetch subscription",
                    "message", e.getMessage()));
        }
    }

    @GetMapping("/validate/ghost-of-march/{subscriptionId}")
    public ResponseEntity<?> validateGhostOfMarch(
            @PathVariable String subscriptionId,
            @RequestParam(required = false) String expected_cancel,
            @RequestParam(required = false) String start_month,
            @RequestParam(required = false) String end_month,
            @RequestParam(required = false) String timezone) {
        ResponseEntity<?> configErr = requireChargebeeConfig();
        if (configErr != null) return configErr;
        try {
            ZoneId zone = parseTimezone(timezone);
            long simulationStart = start_month != null && !start_month.isBlank()
                    ? Simulator.parseStartOfMonth(start_month.trim(), zone)
                    : Instant.now().atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toEpochSecond();
            long simulationEnd = end_month != null && !end_month.isBlank()
                    ? Simulator.parseEndOfMonth(end_month.trim(), zone)
                    : YearMonth.now(zone).plusMonths(18).atEndOfMonth().atTime(23, 59, 59).atZone(zone).toEpochSecond();
            Simulator.SimulationResult result = chargebeeService.validateGhostOfMarch(
                    subscriptionId, expected_cancel, simulationStart, simulationEnd);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Validation failed",
                    "message", e.getMessage()));
        }
    }

    private ZoneId parseTimezone(String timezone) {
        if (timezone != null && !timezone.isBlank()) {
            try {
                return ZoneId.of(timezone.trim());
            } catch (Exception ignored) {}
        }
        return ZoneId.of(defaultTimezone);
    }
}

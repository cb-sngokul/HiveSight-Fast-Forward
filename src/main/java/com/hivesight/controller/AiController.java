package com.hivesight.controller;

import com.hivesight.service.AiService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@CrossOrigin(origins = "*")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        String msg = aiService.isEnabled()
                ? "AI features available"
                : "Set ai.grok.api-key, ai.groq.api-key, ai.openai.api-key, or ai.gemini.api-key to enable";
        return ResponseEntity.ok(Map.of(
                "enabled", aiService.isEnabled(),
                "message", msg
        ));
    }

    @PostMapping("/summarize")
    public ResponseEntity<?> summarize(@RequestBody Map<String, Object> body) {
        if (!aiService.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "AI not configured", "message", "Set ai.openai.api-key in application.properties"));
        }
        try {
            Object data = body.get("data");
            String summary = aiService.generateSummary(data != null ? data : body);
            return ResponseEntity.ok(Map.of("summary", summary != null ? summary : "Unable to generate summary."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "AI failed", "message", e.getMessage()));
        }
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody Map<String, Object> body) {
        if (!aiService.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "AI not configured", "message", "Set ai.openai.api-key in application.properties"));
        }
        try {
            String message = (String) body.get("message");
            Object data = body.get("data");
            String response = aiService.chat(data != null ? data : body, message);
            return ResponseEntity.ok(Map.of("response", response != null ? response : "Unable to generate response."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "AI failed", "message", e.getMessage()));
        }
    }

    @PostMapping("/alerts")
    public ResponseEntity<?> alerts(@RequestBody Map<String, Object> body) {
        if (!aiService.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "AI not configured", "message", "Set ai.openai.api-key in application.properties"));
        }
        try {
            Object data = body.get("data");
            String alertsJson = aiService.generateAlerts(data != null ? data : body);
            return ResponseEntity.ok(Map.of("alerts", parseAlerts(alertsJson)));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "AI failed", "message", e.getMessage()));
        }
    }

    @PostMapping("/summarize-batch")
    public ResponseEntity<?> summarizeBatch(@RequestBody Map<String, Object> body) {
        if (!aiService.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "AI not configured", "message", "Set ai.openai.api-key in application.properties"));
        }
        try {
            @SuppressWarnings("unchecked")
            List<Object> results = (List<Object>) body.get("results");
            String summary = aiService.generateBatchSummary(results != null ? results : List.of());
            return ResponseEntity.ok(Map.of("summary", summary != null ? summary : "Unable to generate batch summary."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "AI failed", "message", e.getMessage()));
        }
    }

    @PostMapping("/alerts-batch")
    public ResponseEntity<?> alertsBatch(@RequestBody Map<String, Object> body) {
        if (!aiService.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "AI not configured", "message", "Set ai.openai.api-key in application.properties"));
        }
        try {
            @SuppressWarnings("unchecked")
            List<Object> results = (List<Object>) body.get("results");
            String alertsJson = aiService.generateBatchAlerts(results != null ? results : List.of());
            return ResponseEntity.ok(Map.of("alerts", parseAlerts(alertsJson)));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "AI failed", "message", e.getMessage()));
        }
    }

    @PostMapping("/chat-batch")
    public ResponseEntity<?> chatBatch(@RequestBody Map<String, Object> body) {
        if (!aiService.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "AI not configured", "message", "Set ai.openai.api-key in application.properties"));
        }
        try {
            String message = (String) body.get("message");
            @SuppressWarnings("unchecked")
            List<Object> results = (List<Object>) body.get("results");
            String response = aiService.chatBatch(results != null ? results : List.of(), message);
            return ResponseEntity.ok(Map.of("response", response != null ? response : "Unable to generate response."));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "AI failed", "message", e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private Object parseAlerts(String json) {
        if (json == null || json.isBlank()) return java.util.List.of();
        try {
            json = json.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('[');
                int end = json.lastIndexOf(']') + 1;
                if (start >= 0 && end > start) json = json.substring(start, end);
            }
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, java.util.List.class);
        } catch (Exception e) {
            return java.util.List.of(Map.of("severity", "info", "title", "Alerts", "message", json));
        }
    }
}

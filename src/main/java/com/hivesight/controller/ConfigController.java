package com.hivesight.controller;

import com.hivesight.service.ChargebeeConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/config")
@CrossOrigin(origins = "*")
public class ConfigController {

    private final ChargebeeConfigService configService;

    public ConfigController(ChargebeeConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/chargebee")
    public Map<String, Object> getChargebeeConfig() {
        return Map.of(
                "site", configService.getSite(),
                "apiKey", configService.getApiKey().replaceAll(".(?=.{4})", "*"), // mask except last 4 chars
                "hasRuntimeConfig", configService.hasRuntimeConfig()
        );
    }

    @PostMapping("/chargebee")
    public ResponseEntity<?> updateChargebeeConfig(@RequestBody Map<String, String> body) {
        String site = body != null ? body.get("site") : null;
        String apiKey = body != null ? body.get("apiKey") : null;
        if (site == null || site.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Site name is required"));
        }
        if ((apiKey == null || apiKey.isBlank()) && !configService.hasRuntimeConfig()) {
            return ResponseEntity.badRequest().body(Map.of("error", "API key is required for first-time setup"));
        }
        try {
            configService.update(site.trim(), apiKey.trim());
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "site", site.trim(),
                    "message", "Configuration saved. Data will be fetched using the new credentials."
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to save configuration",
                    "message", e.getMessage()));
        }
    }

    @DeleteMapping("/chargebee")
    public ResponseEntity<?> clearChargebeeConfig() {
        try {
            configService.clear();
            return ResponseEntity.ok(Map.of("status", "ok", "message", "Signed out"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to clear configuration",
                    "message", e.getMessage()));
        }
    }
}

package com.hivesight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Runtime config for Chargebee site and API key.
 * Values from UI are persisted to chargebee-config.json and override application.properties.
 */
@Service
public class ChargebeeConfigService {

    private static final String CONFIG_FILENAME = "chargebee-config.json";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String defaultSite;
    private final String defaultApiKey;

    private volatile String cachedSite;
    private volatile String cachedApiKey;
    private volatile long lastRead = 0;
    private static final long CACHE_TTL_MS = 5000;

    public ChargebeeConfigService(
            @Value("${chargebee.site:}") String defaultSite,
            @Value("${chargebee.api-key:}") String defaultApiKey) {
        this.defaultSite = defaultSite;
        this.defaultApiKey = defaultApiKey;
    }

    private Path configPath() {
        String base = System.getProperty("user.dir", ".");
        return Paths.get(base, CONFIG_FILENAME);
    }

    public String getSite() {
        ensureLoaded();
        String raw = cachedSite != null && !cachedSite.isBlank() ? cachedSite : defaultSite;
        return sanitizeSite(raw);
    }

    /** Strip protocol and .chargebee.com suffix so we only have the site name (e.g. sngokulraj-test). */
    private String sanitizeSite(String site) {
        if (site == null || site.isBlank()) return site;
        String s = site.trim();
        // Strip protocol
        if (s.startsWith("https://")) s = s.substring(8);
        else if (s.startsWith("http://")) s = s.substring(7);
        else if (s.startsWith("https/")) s = s.substring(6);
        else if (s.startsWith("http/")) s = s.substring(5);
        // Strip .chargebee.com suffix
        if (s.endsWith(".chargebee.com")) s = s.substring(0, s.length() - 13);
        return s.trim();
    }

    public String getApiKey() {
        ensureLoaded();
        return cachedApiKey != null && !cachedApiKey.isBlank() ? cachedApiKey : defaultApiKey;
    }

    private void ensureLoaded() {
        long now = System.currentTimeMillis();
        if (now - lastRead > CACHE_TTL_MS) {
            synchronized (this) {
                if (now - lastRead > CACHE_TTL_MS) {
                    loadFromFile();
                    lastRead = System.currentTimeMillis();
                }
            }
        }
    }

    private void loadFromFile() {
        Path path = configPath();
        if (!Files.exists(path)) {
            cachedSite = null;
            cachedApiKey = null;
            return;
        }
        try {
            String json = Files.readString(path);
            @SuppressWarnings("unchecked")
            Map<String, String> map = objectMapper.readValue(json, Map.class);
            cachedSite = map != null ? map.get("site") : null;
            cachedApiKey = map != null ? map.get("apiKey") : null;
        } catch (IOException e) {
            cachedSite = null;
            cachedApiKey = null;
        }
    }

    /**
     * Update and persist site and API key from UI.
     * If apiKey is blank, keeps existing apiKey from file (for site-only updates).
     */
    public void update(String site, String apiKey) throws IOException {
        if (site == null) site = "";
        if (apiKey == null) apiKey = "";
        site = sanitizeSite(site.trim());
        apiKey = apiKey.trim();
        ensureLoaded();
        String finalSite = site.isEmpty() ? getSite() : site;
        String finalApiKey = apiKey.isEmpty() ? getApiKey() : apiKey;
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("site", finalSite, "apiKey", finalApiKey));
        Path path = configPath();
        Files.writeString(path, json);
        synchronized (this) {
            cachedSite = finalSite;
            cachedApiKey = finalApiKey;
            lastRead = System.currentTimeMillis();
        }
    }

    /**
     * Check if runtime config (from UI) is in use.
     */
    public boolean hasRuntimeConfig() {
        ensureLoaded();
        return (cachedSite != null && !cachedSite.isBlank()) || (cachedApiKey != null && !cachedApiKey.isBlank());
    }

    /**
     * Clear runtime config (sign out).
     */
    public void clear() throws IOException {
        Path path = configPath();
        if (Files.exists(path)) {
            Files.delete(path);
        }
        synchronized (this) {
            cachedSite = null;
            cachedApiKey = null;
            lastRead = 0;
        }
    }
}

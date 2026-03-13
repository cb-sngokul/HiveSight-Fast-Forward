package com.hivesight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class AiService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String provider;
    private final String openaiKey;
    private final String openaiModel;
    private final String groqKey;
    private final String groqModel;
    private final String grokKey;
    private final String grokModel;
    private final String geminiKey;
    private final String geminiModel;
    private final boolean enabled;

    public AiService(
            @Value("${ai.provider:groq}") String provider,
            @Value("${ai.openai.api-key:}") String openaiKey,
            @Value("${ai.openai.model:gpt-4o-mini}") String openaiModel,
            @Value("${ai.groq.api-key:}") String groqKey,
            @Value("${ai.groq.model:llama-3.1-8b-instant}") String groqModel,
            @Value("${ai.grok.api-key:}") String grokKey,
            @Value("${ai.grok.model:grok-3-mini}") String grokModel,
            @Value("${ai.gemini.api-key:}") String geminiKey,
            @Value("${ai.gemini.model:gemini-1.5-flash}") String geminiModel) {
        this.provider = provider != null ? provider.trim().toLowerCase() : "groq";
        this.openaiKey = openaiKey != null ? openaiKey.trim() : "";
        this.openaiModel = openaiModel != null ? openaiModel : "gpt-4o-mini";
        this.groqKey = groqKey != null ? groqKey.trim() : "";
        this.groqModel = groqModel != null ? groqModel : "llama-3.1-8b-instant";
        this.grokKey = grokKey != null ? grokKey.trim() : "";
        this.grokModel = grokModel != null ? grokModel : "grok-3-mini";
        this.geminiKey = geminiKey != null ? geminiKey.trim() : "";
        this.geminiModel = geminiModel != null ? geminiModel : "gemini-1.5-flash";
        this.enabled = ("openai".equals(this.provider) && !this.openaiKey.isEmpty())
                || ("groq".equals(this.provider) && !this.groqKey.isEmpty())
                || ("grok".equals(this.provider) && !this.grokKey.isEmpty())
                || ("gemini".equals(this.provider) && !this.geminiKey.isEmpty());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String generateSummary(Object simulationData) {
        if (!enabled) return null;
        if (simulationData == null) return "No simulation data available.";
        Object aiFriendly = formatAmountsForAi(simulationData);
        if (!(aiFriendly instanceof Map)) {
            String context = toContextString(simulationData);
            return stripSummaryPreamble(callLlm("Summarize this subscription simulation in 2-3 sentences. Use exact values from the data.\n\n" + context));
        }
        Map<String, Object> sf = buildSummaryFields(aiFriendly);
        String prompt = String.format(
            "Generate useful insights for this subscription. Use ONLY these exact values - do not invent, approximate, or use other numbers:\n\n" +
            "EXACT VALUES (copy verbatim):\n" +
            "- Period: %s\n" +
            "- Total projected revenue: %s\n" +
            "- Renewals: %s\n" +
            "- Ramps applied: %s\n" +
            "- Price range: %s\n" +
            "- Notable changes: %s\n" +
            "- Subscription outcome: %s\n\n" +
            "Write 4-6 sentences covering: (1) revenue and renewal overview, (2) ramps and changes - use the EXACT wording from Notable changes (e.g. 'Plan frequency changed from quarterly to monthly' is a billing/plan change, NOT a price change; 'Price changed from X to Y' is a price change), (3) subscription outcome. " +
            "Every number and date must come from the values above. Use Notable changes verbatim. Output ONLY the insights. No preamble.",
            sf.get("simulationWindowDisplay"),
            sf.get("totalProjectedRevenueDisplay"),
            sf.get("renewalsCount"),
            sf.get("rampsCount"),
            sf.get("priceRange"),
            sf.get("notableChanges"),
            sf.get("subscriptionBehavior"));
        String result = callLlm(prompt);
        return stripSummaryPreamble(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSummaryFields(Object aiFriendly) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (!(aiFriendly instanceof Map)) return out;
        Map<String, Object> m = (Map<String, Object>) aiFriendly;
        out.put("simulationWindowDisplay", m.getOrDefault("simulationWindowDisplay", "?"));
        out.put("totalProjectedRevenueDisplay", m.getOrDefault("totalProjectedRevenueDisplay", "?"));
        out.put("renewalsCount", m.getOrDefault("renewalsCount", "?"));
        out.put("subscriptionBehavior", m.getOrDefault("subscriptionBehavior", "?"));
        // Ramps
        java.util.List<?> evList = (java.util.List<?>) m.get("events");
        long rampsCount = evList != null ? evList.stream()
                .filter(e -> e instanceof Map && "ramp_applied".equals(((Map<?, ?>) e).get("type")))
                .count() : 0;
        out.put("rampsCount", rampsCount);
        // Notable changes from monthlyBreakdowns
        java.util.List<String> notableChanges = new java.util.ArrayList<>();
        if (m.get("monthlyBreakdowns") instanceof java.util.List<?> breakdowns) {
            for (Object b : breakdowns) {
                if (b instanceof Map) {
                    Object changes = ((Map<?, ?>) b).get("changes");
                    if (changes instanceof java.util.List<?> cl) {
                        for (Object c : cl) {
                            String s = c != null ? c.toString() : "";
                            if (!s.isEmpty() && !"No changes".equals(s) && !"Initial billing".equals(s)) {
                                String monthLabel = (String) ((Map<?, ?>) b).get("monthLabel");
                                notableChanges.add((monthLabel != null ? monthLabel + ": " : "") + s);
                            }
                        }
                    }
                }
            }
        }
        out.put("notableChanges", notableChanges.isEmpty() ? java.util.List.of("None") : notableChanges);
        // Price range from monthlyBreakdowns
        String priceRange = "—";
        if (m.get("monthlyBreakdowns") instanceof java.util.List<?> breakdowns && !breakdowns.isEmpty()) {
            java.util.List<String> prices = new java.util.ArrayList<>();
            for (Object b : breakdowns) {
                if (b instanceof Map) {
                    Object dp = ((Map<?, ?>) b).get("unitPriceDisplay");
                    if (dp != null) prices.add(dp.toString());
                }
            }
            if (!prices.isEmpty()) {
                java.util.Set<String> unique = new java.util.LinkedHashSet<>(prices);
                priceRange = unique.size() == 1 ? prices.get(0) : String.join(" → ", unique);
            }
        }
        out.put("priceRange", priceRange);
        return out;
    }

    private String toContextStringFromFormatted(Object aiFriendly) {
        try {
            if (aiFriendly == null) return "No data.";
            String json = objectMapper.writeValueAsString(aiFriendly);
            return json.length() > 10000 ? json.substring(0, 10000) + "..." : json;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String stripSummaryPreamble(String text) {
        if (text == null || text.isBlank()) return text;
        text = text.trim();
        String lower = text.toLowerCase();
        String[] preambles = {
            "here are the insights:",
            "here are the key insights:",
            "here is a summary of the chargebee subscription simulation data in plain english:",
            "here is the summary:",
            "here is a brief summary:",
            "in plain english:"
        };
        for (String p : preambles) {
            if (lower.startsWith(p)) {
                text = text.substring(p.length()).trim();
                if (text.startsWith("\"")) text = text.substring(1);
                if (text.endsWith("\"")) text = text.substring(0, text.length() - 1);
                return text.trim();
            }
        }
        return text;
    }

    public String chat(Object simulationData, String userMessage) {
        if (!enabled) return null;
        String context = toContextString(simulationData);
        String prompt = String.format(
            "You are a helpful billing assistant for HiveSight, a Chargebee subscription simulation tool.\n\n" +
            "The user has run a simulation. Here is the simulation data (amounts are in dollars - e.g. 960 = $960):\n%s\n\n" +
            "User question: %s\n\n" +
            "Answer the user's question based on the simulation data. Be concise (2-5 sentences). Use totalProjectedRevenueDisplay and amountDisplay for currency values.",
            context, userMessage != null ? userMessage : "Tell me about this simulation");
        return callLlm(prompt);
    }

    /** Generate batch-level summary from multiple simulation results. */
    public String generateBatchSummary(List<?> results) {
        if (!enabled || results == null || results.isEmpty()) return null;
        String context = buildBatchContext(results);
        if (context == null || context.isBlank()) return "No batch data available.";
        String prompt = String.format(
            "You are a billing analyst. Summarize this BATCH of %d Chargebee subscription simulations.\n\n" +
            "BATCH DATA:\n%s\n\n" +
            "Write 5-8 sentences covering: (1) total projected revenue across all subscriptions, (2) how many end in cancellation vs stay active, " +
            "(3) subscriptions with ramps/scheduled changes and their impact, (4) any high-value or high-risk subscriptions to highlight. " +
            "Use exact values from the data. Output ONLY the insights. No preamble.",
            results.size(), context);
        return stripSummaryPreamble(callLlm(prompt));
    }

    /** Generate batch-level alerts (cross-subscription risks). */
    public String generateBatchAlerts(List<?> results) {
        if (!enabled || results == null || results.isEmpty()) return null;
        String context = buildBatchContext(results);
        if (context == null || context.isBlank()) return null;
        String prompt = String.format(
            "You are a billing risk analyst. Analyze this BATCH of %d subscription simulations and identify cross-subscription risks.\n\n" +
            "BATCH DATA:\n%s\n\n" +
            "Return a JSON array of alert objects. Each alert has: \"severity\" (info/warning/danger), \"title\" (short), \"message\" (1-2 sentences). " +
            "Focus on: revenue concentration, cancellation risk, ramp timing across subs, outliers. Return ONLY the JSON array. If no significant alerts, return [].",
            results.size(), context);
        return callLlm(prompt);
    }

    /** Chat with batch context. */
    public String chatBatch(List<?> results, String userMessage) {
        if (!enabled || results == null || results.isEmpty()) return null;
        String context = buildBatchContext(results);
        if (context == null || context.isBlank()) return null;
        String prompt = String.format(
            "You are a helpful billing assistant for HiveSight. The user ran a BATCH simulation of %d subscriptions.\n\n" +
            "BATCH DATA:\n%s\n\n" +
            "User question: %s\n\n" +
            "Answer based on the batch data. Be concise (2-5 sentences). Use exact values.",
            results.size(), context, userMessage != null ? userMessage : "Summarize this batch");
        return callLlm(prompt);
    }

    @SuppressWarnings("unchecked")
    private String buildBatchContext(List<?> results) {
        try {
            StringBuilder sb = new StringBuilder();
            long totalRevenueCents = 0;
            int cancelledCount = 0;
            int rampCount = 0;
            int i = 0;
            for (Object r : results) {
                if (!(r instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) r;
                String subId = String.valueOf(m.getOrDefault("subscriptionId", "?"));
                Object events = m.get("events");
                long subRevenue = 0;
                boolean cancelled = false;
                long ramps = 0;
                if (events instanceof List<?> evList) {
                    for (Object e : evList) {
                        if (e instanceof Map) {
                            Map<?, ?> ev = (Map<?, ?>) e;
                            if ("cancelled".equals(ev.get("type"))) cancelled = true;
                            if ("ramp_applied".equals(ev.get("type"))) ramps++;
                            Object amt = ev.get("amount");
                            if (amt instanceof Number) subRevenue += ((Number) amt).longValue();
                        }
                    }
                }
                totalRevenueCents += subRevenue;
                if (cancelled) cancelledCount++;
                rampCount += (int) ramps;
                String currency = (String) m.getOrDefault("currencyCode", "USD");
                boolean zeroDec = List.of("JPY", "KRW", "VND", "CLP", "XOF", "XAF").contains(currency);
                String revDisplay = formatCentsToDisplay(subRevenue, currency, zeroDec);
                String outcome = cancelled ? "cancelled" : "active";
                sb.append(String.format("Sub %d: %s | Revenue: %s | Outcome: %s | Ramps: %d\n", ++i, subId, revDisplay, outcome, ramps));
            }
            sb.append(String.format("\nTOTAL: %d subs | Total revenue: %s | Cancelled: %d | Active: %d | Total ramps: %d",
                results.size(), formatCentsToDisplay(totalRevenueCents, "USD", false), cancelledCount, results.size() - cancelledCount, rampCount));
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public String generateAlerts(Object simulationData) {
        if (!enabled) return null;
        String context = toContextString(simulationData);
        String prompt = String.format(
            "You are a billing risk analyst. Analyze this Chargebee subscription simulation and identify potential risks or important alerts.\n\n" +
            "CRITICAL - Contract end: If subscriptionEndDate is null, the subscription AUTO-RENEWS at contract end. Do NOT flag contract end as a risk. " +
            "If events include \"Contract renewed\" or \"renew\" at term end, the subscription continues - do NOT say \"subscription will no longer be active\". " +
            "Only flag contract end as a risk when the subscription actually terminates.\n\n" +
            "CRITICAL - Plan frequency changes: When monthlyBreakdowns show \"Plan frequency changed from quarterly to monthly\" (or similar) with a price change, " +
            "do NOT say \"billing amount reduced may affect revenue\" or \"revenue at risk\". The per-invoice amount may be lower, but billing more frequently (e.g. monthly vs quarterly) " +
            "often results in NET INCREASE in total revenue. Use totalProjectedRevenueDisplay - it is the actual projected total. " +
            "If there was a plan frequency change, use severity \"info\" and say: \"Plan frequency changed from X to Y. Despite lower per-invoice amount, total projected revenue is [totalProjectedRevenueDisplay] due to more frequent billing.\"\n\n" +
            "Note: Amounts are in dollars (960 = $960, 150 = $150). Use totalProjectedRevenueDisplay and amountDisplay.\n\n" +
            "Simulation data:\n%s\n\n" +
            "Return a JSON array of alert objects. Each alert has: \"severity\" (info/warning/danger), \"title\" (short), \"message\" (1-2 sentences). " +
            "Return ONLY the JSON array, no other text. If no significant alerts, return [].",
            context);
        return callLlm(prompt);
    }

    private String toContextString(Object data) {
        try {
            if (data == null) return "No simulation data available.";
            Object aiFriendly = formatAmountsForAi(data);
            String json = objectMapper.writeValueAsString(aiFriendly);
            return json.length() > 12000 ? json.substring(0, 12000) + "..." : json;
        } catch (Exception e) {
            return "Error serializing data: " + e.getMessage();
        }
    }

    /** Convert amounts from cents to dollars so AI interprets correctly (15000 cents = $150, not $15,000). */
    @SuppressWarnings("unchecked")
    private Object formatAmountsForAi(Object data) {
        if (!(data instanceof Map)) return data;
        Map<String, Object> map = new java.util.LinkedHashMap<>((Map<String, Object>) data);
        String currency = (String) map.getOrDefault("currencyCode", "USD");
        boolean zeroDecimal = java.util.List.of("JPY", "KRW", "VND", "CLP", "XOF", "XAF").contains(currency);

        map.put("_note", "Amounts in amountDisplay/totalCentsDisplay are in dollars. Numeric amount/totalCents etc. have been converted: 960 = $960, 150 = $150.");

        // Add human-readable simulation window (use simulationStart/simulationEnd from data)
        Object simStart = map.get("simulationStart");
        Object simEnd = map.get("simulationEnd");
        Object subEnd = map.get("subscriptionEndDate");
        String tz = (String) map.getOrDefault("timezone", "UTC");
        ZoneId zone = ZoneId.of(tz != null && !tz.isBlank() ? tz : "UTC");
        if (simStart instanceof Number && simEnd instanceof Number) {
            String startDisplay = Instant.ofEpochSecond(((Number) simStart).longValue()).atZone(zone).format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));
            String endDisplay = Instant.ofEpochSecond(((Number) simEnd).longValue()).atZone(zone).format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));
            map.put("simulationWindowDisplay", startDisplay + " to " + endDisplay);
        }
        // Derive subscriptionBehavior from simulation events (source of truth) - if cancelled event exists, subscription terminates
        java.util.List<?> eventsList = (java.util.List<?>) map.get("events");
        Object cancelledEvent = eventsList != null ? eventsList.stream()
                .filter(e -> e instanceof Map && "cancelled".equals(((Map<?, ?>) e).get("type")))
                .findFirst().orElse(null) : null;
        if (cancelledEvent instanceof Map<?, ?> ce && ce.get("date") != null) {
            String cancelDateDisplay = Instant.ofEpochSecond(((Number) ce.get("date")).longValue()).atZone(zone).format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));
            map.put("subscriptionBehavior", "Subscription terminates on " + cancelDateDisplay + " (cancelled).");
        } else if (subEnd != null) {
            String endDisplay = Instant.ofEpochSecond(((Number) subEnd).longValue()).atZone(zone).format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));
            map.put("subscriptionBehavior", "Subscription terminates on " + endDisplay + ".");
        } else {
            map.put("subscriptionBehavior", "AUTO-RENEWS at contract end. The subscription does NOT terminate - it continues renewing.");
        }

        if (map.get("events") instanceof java.util.List<?> events) {
            java.util.List<Map<String, Object>> formatted = new java.util.ArrayList<>();
            long totalCents = 0;
            for (Object e : events) {
                if (e instanceof Map) {
                    Map<String, Object> ev = new java.util.LinkedHashMap<>((Map<String, Object>) e);
                    if (ev.get("amount") != null) {
                        long cents = ((Number) ev.get("amount")).longValue();
                        totalCents += cents;
                        ev.put("amount", formatCentsToDollars(cents, currency, zeroDecimal));
                        ev.put("amountDisplay", formatCentsToDisplay(cents, currency, zeroDecimal));
                    }
                    formatted.add(ev);
                }
            }
            map.put("events", formatted);
            map.put("totalProjectedRevenueDisplay", formatCentsToDisplay(totalCents, currency, zeroDecimal));
            map.put("totalProjectedRevenue", formatCentsToDollars(totalCents, currency, zeroDecimal));
            long renewals = formatted.stream().filter(m -> "renewal".equals(m.get("type"))).count();
            map.put("renewalsCount", renewals);
        }

        if (map.get("monthlyBreakdowns") instanceof java.util.List<?> breakdowns) {
            java.util.List<Map<String, Object>> formatted = new java.util.ArrayList<>();
            for (Object b : breakdowns) {
                if (b instanceof Map) {
                    Map<String, Object> bd = new java.util.LinkedHashMap<>((Map<String, Object>) b);
                    formatAmountField(bd, "unitPrice", currency, zeroDecimal);
                    formatAmountField(bd, "subtotalCents", currency, zeroDecimal);
                    formatAmountField(bd, "discountCents", currency, zeroDecimal);
                    formatAmountField(bd, "taxCents", currency, zeroDecimal);
                    formatAmountField(bd, "totalCents", currency, zeroDecimal);
                    formatAmountField(bd, "impactVsPreviousCents", currency, zeroDecimal);
                    formatted.add(bd);
                }
            }
            map.put("monthlyBreakdowns", formatted);
        }
        return map;
    }

    private void formatAmountField(Map<String, Object> map, String key, String currency, boolean zeroDecimal) {
        Object val = map.get(key);
        if (val != null) {
            long cents = ((Number) val).longValue();
            map.put(key, formatCentsToDollars(cents, currency, zeroDecimal));
            map.put(key + "Display", formatCentsToDisplay(cents, currency, zeroDecimal));
        }
    }

    private String formatCentsToDollars(long cents, String currency, boolean zeroDecimal) {
        double val = zeroDecimal ? cents : (cents / 100.0);
        return String.format("%.2f", val);
    }

    private String formatCentsToDisplay(long cents, String currency, boolean zeroDecimal) {
        double val = zeroDecimal ? cents : (cents / 100.0);
        return "$" + String.format("%,.2f", val);
    }

    private String callLlm(String userPrompt) {
        if ("gemini".equals(provider)) {
            return callGemini(userPrompt);
        }
        if ("groq".equals(provider)) {
            return callGroq(userPrompt);
        }
        if ("grok".equals(provider)) {
            return callGrok(userPrompt);
        }
        return callOpenAi(userPrompt);
    }

    private String callGrok(String userPrompt) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(grokKey);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", grokModel);
            ArrayNode messages = body.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    "https://api.x.ai/v1/chat/completions",
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            if (response.getBody() != null) {
                Object choices = response.getBody().get("choices");
                if (choices instanceof java.util.List<?> list && !list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof Map) {
                        Object message = ((Map<?, ?>) first).get("message");
                        if (message instanceof Map) {
                            Object content = ((Map<?, ?>) message).get("content");
                            return content != null ? content.toString().trim() : null;
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("AI service error: " + e.getMessage());
        }
        return null;
    }

    private String callGroq(String userPrompt) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(groqKey);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", groqModel);
            ArrayNode messages = body.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    "https://api.groq.com/openai/v1/chat/completions",
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            if (response.getBody() != null) {
                Object choices = response.getBody().get("choices");
                if (choices instanceof java.util.List<?> list && !list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof Map) {
                        Object message = ((Map<?, ?>) first).get("message");
                        if (message instanceof Map) {
                            Object content = ((Map<?, ?>) message).get("content");
                            return content != null ? content.toString().trim() : null;
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("AI service error: " + e.getMessage());
        }
        return null;
    }

    private String callOpenAi(String userPrompt) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openaiKey);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", openaiModel);
            ArrayNode messages = body.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    "https://api.openai.com/v1/chat/completions",
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            if (response.getBody() != null) {
                Object choices = response.getBody().get("choices");
                if (choices instanceof java.util.List<?> list && !list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof Map) {
                        Object message = ((Map<?, ?>) first).get("message");
                        if (message instanceof Map) {
                            Object content = ((Map<?, ?>) message).get("content");
                            return content != null ? content.toString().trim() : null;
                        }
                    }
                }
            }
        } catch (HttpClientErrorException e) {
            String msg = parseOpenAiError(e);
            throw new RuntimeException(msg);
        } catch (Exception e) {
            throw new RuntimeException("AI service error: " + e.getMessage());
        }
        return null;
    }

    private String parseOpenAiError(HttpClientErrorException e) {
        if (e.getStatusCode().value() == 429) {
            try {
                String body = e.getResponseBodyAsString();
                if (body != null && body.contains("insufficient_quota")) {
                    return "OpenAI quota exceeded. Switch to Groq (free): set ai.provider=groq and ai.groq.api-key=xxx. Get key at console.groq.com";
                }
                if (body != null && body.contains("rate_limit")) {
                    return "OpenAI rate limit reached. Please wait a moment and try again.";
                }
            } catch (Exception ignored) {}
            return "OpenAI rate limit reached. Try again later or switch to Groq (free).";
        }
        return "AI service error: " + e.getMessage();
    }

    private String callGemini(String userPrompt) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", geminiKey);

            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode contents = body.putArray("contents");
            ObjectNode content = contents.addObject();
            ArrayNode parts = content.putArray("parts");
            ObjectNode part = parts.addObject();
            part.put("text", userPrompt);

            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

            if (response.getBody() != null) {
                Object candidates = response.getBody().get("candidates");
                if (candidates instanceof java.util.List<?> list && !list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof Map) {
                        Object contentObj = ((Map<?, ?>) first).get("content");
                        if (contentObj instanceof Map) {
                            Object partsObj = ((Map<?, ?>) contentObj).get("parts");
                            if (partsObj instanceof java.util.List<?> partsList && !partsList.isEmpty()) {
                                Object partObj = partsList.get(0);
                                if (partObj instanceof Map) {
                                    Object text = ((Map<?, ?>) partObj).get("text");
                                    return text != null ? text.toString().trim() : null;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("AI service error: " + e.getMessage());
        }
        return null;
    }
}

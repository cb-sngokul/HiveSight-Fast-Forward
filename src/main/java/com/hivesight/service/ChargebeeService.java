package com.hivesight.service;

import com.hivesight.engine.Ramp;
import com.hivesight.engine.Ramp.ItemToAdd;
import com.hivesight.engine.Ramp.ItemToUpdate;
import com.hivesight.engine.SimulatedSubscription;
import com.hivesight.engine.Simulator;
import com.hivesight.engine.TimelineEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class ChargebeeService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;

    private final String apiKey;

    public ChargebeeService(
            @Value("${chargebee.site:your-site-test}") String site,
            @Value("${chargebee.api-key:test_xxxxxxxx}") String apiKey) {
        this.baseUrl = "https://" + site + ".chargebee.com/api/v2";
        this.apiKey = apiKey;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth(apiKey, "");
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    public List<Map<String, Object>> listSubscriptions(boolean hasScheduledChangesOnly) {
        String url = baseUrl + "/subscriptions?limit=50";
        if (hasScheduledChangesOnly) {
            url += "&subscription[has_scheduled_changes][is]=true";
        }
        ResponseEntity<Map> resp = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Map.class
        );
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) resp.getBody().get("list");
        if (list == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> entry : list) {
            result.add((Map<String, Object>) entry.get("subscription"));
        }
        return result;
    }

    public Map<String, Object> getSubscription(String subscriptionId) {
        String url = baseUrl + "/subscriptions/" + subscriptionId;
        ResponseEntity<Map> resp = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Map.class
        );
        return (Map<String, Object>) resp.getBody().get("subscription");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getItemPrice(String itemPriceId) {
        try {
            String url = baseUrl + "/item_prices/" + itemPriceId;
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders()),
                    Map.class
            );
            return (Map<String, Object>) resp.getBody().get("item_price");
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    public List<Ramp> listRamps(String subscriptionId) {
        String url = baseUrl + "/ramps?limit=100&subscription_id[in]=[\"" + subscriptionId + "\"]";
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders()),
                    Map.class
            );
            List<Map<String, Object>> list = (List<Map<String, Object>>) resp.getBody().get("list");
            if (list == null) return List.of();
            List<Ramp> ramps = new ArrayList<>();
            for (Map<String, Object> entry : list) {
                Map<String, Object> r = (Map<String, Object>) entry.get("ramp");
                if (r != null) ramps.add(rampFromMap(r));
            }
            return ramps;
        } catch (Exception e) {
            return List.of();
        }
    }

    public Simulator.SimulationResult simulate(String subscriptionId, int months) {
        Map<String, Object> subMap = getSubscription(subscriptionId);
        List<Ramp> ramps = listRamps(subscriptionId);

        SimulatedSubscription sub = Simulator.toSimulatedSubscription(subMap);
        return Simulator.simulate(sub, ramps, months);
    }

    public Simulator.SimulationResult validateGhostOfMarch(String subscriptionId, String expectedCancelDate) {
        Simulator.SimulationResult result = simulate(subscriptionId, 18);
        var lastEvent = result.events().isEmpty() ? null : result.events().get(result.events().size() - 1);

        boolean passed = lastEvent != null
                && TimelineEvent.TYPE_CANCELLED.equals(lastEvent.type())
                && (expectedCancelDate == null || expectedCancelDate.isEmpty() || expectedCancelDate.equals(lastEvent.dateFormatted()));

        String message = passed
                ? "Subscription correctly terminates on " + lastEvent.dateFormatted()
                : expectedCancelDate != null && !expectedCancelDate.isEmpty()
                        ? "Expected cancel on " + expectedCancelDate + ", got " + (lastEvent != null ? lastEvent.type() + " on " + lastEvent.dateFormatted() : "no events")
                        : "Last event: " + (lastEvent != null ? lastEvent.type() + " on " + lastEvent.dateFormatted() : "none");

        return new Simulator.SimulationResult(
                result.subscriptionId(),
                result.customerId(),
                result.simulationStart(),
                result.simulationEnd(),
                result.events(),
                result.chargebeeUiNextBilling(),
                result.hivesightNextBilling(),
                passed,
                message
        );
    }

    @SuppressWarnings("unchecked")
    private Ramp rampFromMap(Map<String, Object> r) {
        Object ef = r.get("effective_from");
        long effectiveFrom = ef instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(ef));

        List<ItemToAdd> toAdd = null;
        if (r.get("items_to_add") instanceof List<?> la) {
            toAdd = new ArrayList<>();
            for (Object o : la) {
                Map<String, Object> a = (Map<String, Object>) o;
                Integer billingPeriod = a.get("billing_period") != null ? ((Number) a.get("billing_period")).intValue() : null;
                String billingPeriodUnit = (String) a.get("billing_period_unit");
                // Chargebee ramp API may omit billing_period for items_to_add; fetch from item price if needed
                if ("plan".equals(a.get("item_type")) && billingPeriod == null) {
                    String itemPriceId = (String) a.get("item_price_id");
                    if (itemPriceId != null) {
                        Map<String, Object> ip = getItemPrice(itemPriceId);
                        if (!ip.isEmpty()) {
                            billingPeriod = ip.get("period") != null ? ((Number) ip.get("period")).intValue() : null;
                            billingPeriodUnit = (String) ip.get("period_unit");
                        }
                    }
                }
                toAdd.add(new ItemToAdd(
                        (String) a.get("item_price_id"),
                        (String) a.get("item_type"),
                        a.get("quantity") != null ? ((Number) a.get("quantity")).intValue() : null,
                        a.get("unit_price") != null ? ((Number) a.get("unit_price")).longValue() : null,
                        billingPeriod,
                        billingPeriodUnit
                ));
            }
        }

        List<ItemToUpdate> toUpdate = null;
        if (r.get("items_to_update") instanceof List<?> lu) {
            toUpdate = new ArrayList<>();
            for (Object o : lu) {
                Map<String, Object> u = (Map<String, Object>) o;
                toUpdate.add(new ItemToUpdate(
                        (String) u.get("item_price_id"),
                        (String) u.get("item_type"),
                        u.get("quantity") != null ? ((Number) u.get("quantity")).intValue() : null,
                        u.get("unit_price") != null ? ((Number) u.get("unit_price")).longValue() : null,
                        u.get("billing_period") != null ? ((Number) u.get("billing_period")).intValue() : null,
                        (String) u.get("billing_period_unit")
                ));
            }
        }

        List<String> toRemove = r.get("items_to_remove") != null ? (List<String>) r.get("items_to_remove") : null;
        return new Ramp(
                (String) r.get("id"),
                (String) r.get("subscription_id"),
                effectiveFrom,
                (String) r.get("status"),
                toAdd,
                toUpdate,
                toRemove
        );
    }
}

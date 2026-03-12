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

import java.time.ZoneId;
import java.util.*;

@Service
public class ChargebeeService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;

    private final String apiKey;

    private final ZoneId siteTimezone;

    public ChargebeeService(
            @Value("${chargebee.site:your-site-test}") String site,
            @Value("${chargebee.api-key:test_xxxxxxxx}") String apiKey,
            @Value("${chargebee.timezone:Asia/Kolkata}") String timezone) {
        this.baseUrl = "https://" + site + ".chargebee.com/api/v2";
        this.apiKey = apiKey;
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (Exception e) {
            zone = ZoneId.of("Asia/Kolkata");
        }
        this.siteTimezone = zone;
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
        return Simulator.simulate(sub, ramps, months, siteTimezone);
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
                result.currencyCode(),
                result.timezone(),
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
                Integer billingCycles = a.get("billing_cycles") != null ? ((Number) a.get("billing_cycles")).intValue() : null;
                // Chargebee ramp API may omit billing_period for items_to_add; fetch from item price if needed
                String itemType = (String) a.get("item_type");
                if ("plan".equals(itemType) && billingPeriod == null) {
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
                        itemType != null ? itemType : "addon",
                        a.get("quantity") != null ? ((Number) a.get("quantity")).intValue() : null,
                        a.get("unit_price") != null ? ((Number) a.get("unit_price")).longValue() : null,
                        billingPeriod,
                        billingPeriodUnit,
                        billingCycles
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
                        (String) u.get("billing_period_unit"),
                        u.get("billing_cycles") != null ? ((Number) u.get("billing_cycles")).intValue() : null
                ));
            }
        }

        List<String> toRemove = parseStringOrList(r.get("items_to_remove"));
        List<Ramp.CouponToAdd> couponsToAdd = parseCouponsToAdd(r.get("coupons_to_add"));
        List<String> couponsToRemove = parseStringOrList(r.get("coupons_to_remove"));
        List<Ramp.DiscountToAdd> discountsToAdd = parseDiscountsToAdd(r.get("discounts_to_add"));
        List<String> discountsToRemove = parseStringOrList(r.get("discounts_to_remove"));

        return new Ramp(
                (String) r.get("id"),
                (String) r.get("subscription_id"),
                effectiveFrom,
                (String) r.get("status"),
                toAdd,
                toUpdate,
                toRemove,
                couponsToAdd,
                couponsToRemove,
                discountsToAdd,
                discountsToRemove
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> parseStringOrList(Object val) {
        if (val == null) return null;
        if (val instanceof List<?> list) {
            return list.stream()
                    .map(o -> o instanceof Map<?, ?> m ? String.valueOf(((Map<?, ?>) m).get("item_price_id")) : String.valueOf(o))
                    .filter(s -> s != null && !s.equals("null") && !s.isBlank())
                    .toList();
        }
        if (val instanceof String) {
            String s = (String) val;
            if (!s.isBlank()) return List.of(s.split(",\\s*"));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Ramp.CouponToAdd> parseCouponsToAdd(Object val) {
        if (val instanceof List<?> la) {
            List<Ramp.CouponToAdd> result = new ArrayList<>();
            for (Object o : la) {
                Map<String, Object> a = (Map<String, Object>) o;
                Long applyTill = a.get("apply_till") != null ? ((Number) a.get("apply_till")).longValue() : null;
                result.add(new Ramp.CouponToAdd((String) a.get("coupon_id"), applyTill));
            }
            return result.isEmpty() ? null : result;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Ramp.DiscountToAdd> parseDiscountsToAdd(Object val) {
        if (val instanceof List<?> la) {
            List<Ramp.DiscountToAdd> result = new ArrayList<>();
            for (Object o : la) {
                Map<String, Object> a = (Map<String, Object>) o;
                result.add(new Ramp.DiscountToAdd(
                        (String) a.get("id"),
                        (String) a.get("duration_type"),
                        a.get("percentage") != null ? ((Number) a.get("percentage")).doubleValue() : null,
                        a.get("amount") != null ? ((Number) a.get("amount")).longValue() : null,
                        (String) a.get("apply_on"),
                        (String) a.get("item_price_id")
                ));
            }
            return result.isEmpty() ? null : result;
        }
        return null;
    }
}

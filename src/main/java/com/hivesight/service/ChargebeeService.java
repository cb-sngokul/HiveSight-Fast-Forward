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
    private final ChargebeeConfigService configService;
    private final ZoneId siteTimezone;
    private final String defaultTaxRate;

    public ChargebeeService(
            ChargebeeConfigService configService,
            @Value("${chargebee.timezone:Asia/Kolkata}") String timezone,
            @Value("${chargebee.default-tax-rate:}") String defaultTaxRate) {
        this.configService = configService;
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (Exception e) {
            zone = ZoneId.of("Asia/Kolkata");
        }
        this.siteTimezone = zone;
        this.defaultTaxRate = defaultTaxRate != null && !defaultTaxRate.isBlank() ? defaultTaxRate : null;
    }

    private String baseUrl() {
        return "https://" + configService.getSite() + ".chargebee.com/api/v2";
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth(configService.getApiKey(), "");
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    public List<Map<String, Object>> listSubscriptions(boolean hasScheduledChangesOnly) {
        String url = baseUrl() + "/subscriptions?limit=50";
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

    public Map<String, Object> getSubscriptionDetails(String subscriptionId) {
        Map<String, Object> sub = getSubscription(subscriptionId);
        List<Ramp> ramps = listRamps(subscriptionId);
        return buildSubscriptionDetailsCard(sub, ramps);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSubscriptionDetailsCard(Map<String, Object> sub, List<Ramp> ramps) {
        Map<String, Object> card = new java.util.LinkedHashMap<>();
        card.put("id", sub.get("id"));
        card.put("status", sub.get("status"));
        card.put("currencyCode", sub.getOrDefault("currency_code", "USD"));

        // Contract term - use effective end date considering ramps (billing period changes)
        Long contractStart = null;
        Long rawContractEnd = null;
        if (sub.get("contract_term") instanceof Map<?, ?> ct) {
            Map<String, Object> ctMap = (Map<String, Object>) ct;
            if (ctMap.get("contract_start") != null) contractStart = ((Number) ctMap.get("contract_start")).longValue();
            if (ctMap.get("contract_end") != null) rawContractEnd = ((Number) ctMap.get("contract_end")).longValue();
        }
        if (contractStart == null) contractStart = sub.get("current_term_start") != null ? ((Number) sub.get("current_term_start")).longValue() : null;
        if (rawContractEnd == null) rawContractEnd = sub.get("current_term_end") != null ? ((Number) sub.get("current_term_end")).longValue() : null;

        SimulatedSubscription simSub = Simulator.toSimulatedSubscription(sub);
        Long effectiveContractEnd = Simulator.computeEffectiveContractEnd(simSub, ramps, siteTimezone);
        Long contractEnd = effectiveContractEnd != null ? effectiveContractEnd : rawContractEnd;

        card.put("contractStart", contractStart);
        card.put("contractEnd", contractEnd);

        // Billing (from plan item or subscription root)
        int period = 1;
        String unit = "month";
        List<Map<String, Object>> items = (List<Map<String, Object>>) sub.getOrDefault("subscription_items", List.of());
        var planItem = items.stream().filter(i -> "plan".equals(i.get("item_type"))).findFirst().orElse(Map.<String, Object>of());
        if (planItem.get("billing_period") != null) period = ((Number) planItem.get("billing_period")).intValue();
        else if (sub.get("billing_period") != null) period = ((Number) sub.get("billing_period")).intValue();
        if (planItem.get("billing_period_unit") != null) unit = (String) planItem.get("billing_period_unit");
        else if (sub.get("billing_period_unit") != null) unit = (String) sub.get("billing_period_unit");
        card.put("billingPeriod", period);
        card.put("billingPeriodUnit", unit);
        card.put("billingDisplay", formatBillingDisplay(period, unit));

        // Tax - Chargebee subscription doesn't expose tax rate; use config default if set
        card.put("taxRate", defaultTaxRate);

        // Upcoming changes from ramps
        List<String> upcomingChanges = new ArrayList<>();
        for (Ramp r : ramps.stream().filter(r -> "scheduled".equals(r.status())).toList()) {
            String dateStr = formatEpochDate(r.effectiveFrom());
            if (r.itemsToUpdate() != null) {
                for (Ramp.ItemToUpdate u : r.itemsToUpdate()) {
                    if (u.quantity() != null) upcomingChanges.add("Quantity changes to " + u.quantity() + " on " + dateStr);
                    else if (u.unitPrice() != null) upcomingChanges.add("Price changes on " + dateStr);
                    else if (u.billingPeriod() != null) upcomingChanges.add("Billing period changes on " + dateStr);
                    else upcomingChanges.add("Item updated on " + dateStr);
                }
            }
            if (r.itemsToAdd() != null) {
                for (Ramp.ItemToAdd a : r.itemsToAdd()) {
                    String qty = a.quantity() != null ? " (qty " + a.quantity() + ")" : "";
                    upcomingChanges.add("Add " + (a.itemPriceId() != null ? a.itemPriceId() : "item") + qty + " on " + dateStr);
                }
            }
            if (r.itemsToRemove() != null) {
                for (String itemId : r.itemsToRemove()) {
                    upcomingChanges.add("Remove " + itemId + " on " + dateStr);
                }
            }
        }
        card.put("upcomingChanges", upcomingChanges);
        card.put("timezone", siteTimezone.getId());
        return card;
    }

    private String formatBillingDisplay(int period, String unit) {
        return switch (unit.toLowerCase()) {
            case "month" -> period == 1 ? "Monthly" : "Every " + period + " months";
            case "year" -> period == 1 ? "Yearly" : "Every " + period + " years";
            case "week" -> period == 1 ? "Weekly" : "Every " + period + " weeks";
            case "day" -> period == 1 ? "Daily" : "Every " + period + " days";
            default -> period + " " + unit + "(s)";
        };
    }

    private String formatEpochDate(long epochSec) {
        return java.time.Instant.ofEpochSecond(epochSec).atZone(siteTimezone).format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"));
    }

    public Map<String, Object> getSubscription(String subscriptionId) {
        String url = baseUrl() + "/subscriptions/" + subscriptionId;
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
            String url = baseUrl() + "/item_prices/" + itemPriceId;
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
        String url = baseUrl() + "/ramps?limit=100&subscription_id[in]=[\"" + subscriptionId + "\"]";
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

    public Simulator.SimulationResult simulate(String subscriptionId, long simulationStart, long simulationEnd) {
        Map<String, Object> subMap = getSubscription(subscriptionId);
        List<Ramp> ramps = listRamps(subscriptionId);

        SimulatedSubscription sub = Simulator.toSimulatedSubscription(subMap);
        Long subscriptionEndDate = Simulator.subscriptionEndDate(subMap);
        Integer taxRate = null;
        if (defaultTaxRate != null && !defaultTaxRate.isBlank()) {
            try { taxRate = Integer.parseInt(defaultTaxRate); } catch (NumberFormatException ignored) {}
        }
        return Simulator.simulate(sub, ramps, simulationStart, simulationEnd, subscriptionEndDate, siteTimezone, taxRate);
    }

    public Simulator.SimulationResult validateGhostOfMarch(String subscriptionId, String expectedCancelDate, long simulationStart, long simulationEnd) {
        Simulator.SimulationResult result = simulate(subscriptionId, simulationStart, simulationEnd);
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
                result.subscriptionEndDate(),
                result.events(),
                result.monthlyBreakdowns(),
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

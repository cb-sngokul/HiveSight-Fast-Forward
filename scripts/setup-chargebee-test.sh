#!/bin/bash
# HiveSight Chargebee Test Setup
# Creates: Item Family, Item, Item Prices, Customer, Subscription, Ramp
# Uses unique IDs prefixed with hivesight_ to avoid conflicts with existing data

set -e
SITE="${CHARGEBEE_SITE:-sngokulraj-test}"
API_KEY="${CHARGEBEE_API_KEY:-test_0bB85cuLUqzfC8pbm9YN0oDD0PCacdPsZj}"
BASE_URL="https://${SITE}.chargebee.com/api/v2"

# Unique prefix for all resources
PREFIX="hivesight_"
ITEM_FAMILY_ID="${PREFIX}family"
ITEM_ID="${PREFIX}plan"
# Using yearly + monthly (quarterly requires site config for 3-month frequency)
ITEM_PRICE_YEARLY="${PREFIX}plan-USD-yearly"
ITEM_PRICE_MONTHLY="${PREFIX}plan-USD-monthly"
CUSTOMER_ID="${PREFIX}test-customer"

echo "=== HiveSight Chargebee Test Setup ==="
echo "Site: $SITE"
echo ""

# 1. Create Item Family
echo "1. Creating Item Family..."
curl -s -X POST "${BASE_URL}/item_families" \
  -u "${API_KEY}:" \
  -d "id=${ITEM_FAMILY_ID}" \
  -d "name=HiveSight Test Family" \
  -d "description=Item family for HiveSight app testing" | jq -r '.item_family.id // .message // .'
echo ""

# 2. Create Item (Plan)
echo "2. Creating Item (Plan)..."
curl -s -X POST "${BASE_URL}/items" \
  -u "${API_KEY}:" \
  -d "id=${ITEM_ID}" \
  -d "name=HiveSight Test Plan" \
  -d "type=plan" \
  -d "item_family_id=${ITEM_FAMILY_ID}" \
  -d "item_applicability=all" | jq -r '.item.id // .message // .'
echo ""

# 3. Create Item Price - Yearly (1 year)
echo "3. Creating Item Price (Yearly)..."
curl -s -X POST "${BASE_URL}/item_prices" \
  -u "${API_KEY}:" \
  -d "id=${ITEM_PRICE_YEARLY}" \
  -d "item_id=${ITEM_ID}" \
  -d "name=HiveSight Plan Yearly" \
  -d "pricing_model=per_unit" \
  -d "price=99000" \
  -d "period=1" \
  -d "period_unit=year" \
  -d "external_name=HiveSight Yearly" | jq -r '.item_price.id // .message // .'
echo ""

# 4. Create Item Price - Monthly (1 month)
echo "4. Creating Item Price (Monthly)..."
curl -s -X POST "${BASE_URL}/item_prices" \
  -u "${API_KEY}:" \
  -d "id=${ITEM_PRICE_MONTHLY}" \
  -d "item_id=${ITEM_ID}" \
  -d "name=HiveSight Plan Monthly" \
  -d "pricing_model=per_unit" \
  -d "price=3300" \
  -d "period=1" \
  -d "period_unit=month" \
  -d "external_name=HiveSight Monthly" | jq -r '.item_price.id // .message // .'
echo ""

# 5. Create Customer (or use existing)
echo "5. Creating Customer..."
CUSTOMER_RESP=$(curl -s -X POST "${BASE_URL}/customers" \
  -u "${API_KEY}:" \
  -d "id=${CUSTOMER_ID}" \
  -d "first_name=HiveSight" \
  -d "last_name=TestUser" \
  -d "email=hivesight-test@example.com")
CUST_ERR=$(echo "$CUSTOMER_RESP" | jq -r '.error_msg // empty')
if [ -n "$CUST_ERR" ] && [[ "$CUST_ERR" == *"already present"* ]]; then
  echo "Customer already exists: $CUSTOMER_ID"
else
  echo "$CUSTOMER_RESP" | jq -r '.customer.id // .message // .'
fi
echo ""

# 5b. Add test card to customer (required for subscription creation)
echo "5b. Adding test card to customer..."
CARD_RESP=$(curl -s -X POST "${BASE_URL}/payment_sources/create_card" \
  -u "${API_KEY}:" \
  -d "customer_id=${CUSTOMER_ID}" \
  -d "card[number]=4111111111111111" \
  -d "card[expiry_month]=12" \
  -d "card[expiry_year]=2028" \
  -d "card[cvv]=100" \
  -d "replace_primary_payment_source=true")
CARD_ERR=$(echo "$CARD_RESP" | jq -r '.error_msg // empty')
if [ -n "$CARD_ERR" ]; then
  echo "Card add response: $CARD_RESP" | jq .
  echo "Note: If card already exists, subscription creation may still work."
else
  echo "Card added successfully"
fi
echo ""

# 6. Create Subscription (with yearly plan)
echo "6. Creating Subscription (Yearly plan)..."
SUB_RESP=$(curl -s -X POST "${BASE_URL}/customers/${CUSTOMER_ID}/subscription_for_items" \
  -u "${API_KEY}:" \
  -d "subscription_items[item_price_id][0]=${ITEM_PRICE_YEARLY}" \
  -d "subscription_items[quantity][0]=1")
SUB_ID=$(echo "$SUB_RESP" | jq -r '.subscription.id // empty')
if [ -z "$SUB_ID" ]; then
  echo "Subscription creation failed:"
  echo "$SUB_RESP" | jq .
  exit 1
fi
echo "Subscription ID: $SUB_ID"
echo ""

# 7. Create Ramp - Switch from Yearly to Monthly
# effective_from: 60 days from now (Unix timestamp)
EFFECTIVE_FROM=$(($(date +%s) + 60 * 86400))
echo "7. Creating Ramp (effective_from=$EFFECTIVE_FROM, ~60 days from now)..."
echo "   Using items_to_update: change billing_period to 1 month"
RAMP_RESP=$(curl -s -X POST "${BASE_URL}/subscriptions/${SUB_ID}/create_ramp" \
  -u "${API_KEY}:" \
  -d "effective_from=${EFFECTIVE_FROM}" \
  -d "description=HiveSight test: Yearly to Monthly ramp" \
  -d "items_to_update[item_price_id][0]=${ITEM_PRICE_YEARLY}" \
  -d "items_to_update[item_type][0]=plan" \
  -d "items_to_update[billing_period][0]=1" \
  -d "items_to_update[billing_period_unit][0]=month")

RAMP_ID=$(echo "$RAMP_RESP" | jq -r '.ramp.id // empty')
if [ -z "$RAMP_ID" ]; then
  echo "Ramp creation response:"
  echo "$RAMP_RESP" | jq .
  echo ""
  echo "Trying alternative: items_to_add + items_to_remove..."
  RAMP_RESP2=$(curl -s -X POST "${BASE_URL}/subscriptions/${SUB_ID}/create_ramp" \
    -u "${API_KEY}:" \
    -d "effective_from=${EFFECTIVE_FROM}" \
    -d "description=HiveSight test: Switch to Monthly plan" \
    -d "items_to_remove[0]=${ITEM_PRICE_YEARLY}" \
    -d "items_to_add[item_price_id][0]=${ITEM_PRICE_MONTHLY}" \
    -d "items_to_add[item_type][0]=plan" \
    -d "items_to_add[quantity][0]=1" \
    -d "items_to_add[billing_period][0]=1" \
    -d "items_to_add[billing_period_unit][0]=month")
  RAMP_ID=$(echo "$RAMP_RESP2" | jq -r '.ramp.id // empty')
  echo "$RAMP_RESP2" | jq .
fi

if [ -n "$RAMP_ID" ]; then
  echo "Ramp ID: $RAMP_ID"
fi
echo ""

echo "=== Setup Complete ==="
echo ""
echo "Summary:"
echo "  Customer ID:     $CUSTOMER_ID"
echo "  Subscription ID: $SUB_ID"
echo "  Item Price (Yearly):  $ITEM_PRICE_YEARLY"
echo "  Item Price (Monthly): $ITEM_PRICE_MONTHLY"
echo ""
echo "To test HiveSight:"
echo "  1. Run with: CHARGEBEE_SITE=sngokulraj-test CHARGEBEE_API_KEY=<your-api-key> ./mvnw spring-boot:run"
echo "  2. Open http://localhost:8765 and enter Subscription ID: $SUB_ID"
echo "  3. Click 'Simulate 18 Months' to see the ramp applied in the timeline"
echo "  4. Or test via API: curl http://localhost:8765/api/simulate/$SUB_ID"
echo ""

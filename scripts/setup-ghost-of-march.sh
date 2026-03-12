#!/bin/bash
# HiveSight Ghost of March Test Setup
# Creates a subscription that cancels on Sept 15, 2027 - for validating Ghost of March
# Scenario: Yearly plan + Ramp to Monthly (Oct 1, 2026) + Cancel Sept 10, 2027

set -e
SITE="${CHARGEBEE_SITE:-sngokulraj-test}"
API_KEY="${CHARGEBEE_API_KEY:-test_0bB85cuLUqzfC8pbm9YN0oDD0PCacdPsZj}"
BASE_URL="https://${SITE}.chargebee.com/api/v2"

# Reuse existing catalog
PREFIX="hivesight_"
ITEM_PRICE_YEARLY="${PREFIX}plan-USD-yearly"
ITEM_PRICE_MONTHLY="${PREFIX}plan-USD-monthly"
CUSTOMER_ID="${PREFIX}test-customer"

# Sept 10, 2027 00:00 UTC - must be within 18-month window (ends ~Sept 12, 2027)
CANCEL_AT=1820534400
# Oct 1, 2026 00:00 UTC - ramp must be BEFORE first renewal (March 2027) so simulator applies it
RAMP_EFFECTIVE=1790812800

echo "=== HiveSight Ghost of March Setup ==="
echo "Site: $SITE"
echo "Expected cancel date: 2027-09-10"
echo ""

# Uses existing customer - run setup-chargebee-test.sh first to create customer + card
echo "Using customer: $CUSTOMER_ID"
echo ""

# 1. Create Subscription (yearly plan)
echo "1. Creating Subscription (Yearly plan)..."
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

# 2. Add Ramp (yearly → monthly, effective Oct 1, 2026)
echo "2. Creating Ramp (Yearly → Monthly, effective Oct 1, 2026)..."
RAMP_RESP=$(curl -s -X POST "${BASE_URL}/subscriptions/${SUB_ID}/create_ramp" \
  -u "${API_KEY}:" \
  -d "effective_from=${RAMP_EFFECTIVE}" \
  -d "description=Ghost of March: Switch to Monthly before Nov 30 cancel" \
  -d "items_to_remove[0]=${ITEM_PRICE_YEARLY}" \
  -d "items_to_add[item_price_id][0]=${ITEM_PRICE_MONTHLY}" \
  -d "items_to_add[item_type][0]=plan" \
  -d "items_to_add[quantity][0]=1" \
  -d "items_to_add[billing_period][0]=1" \
  -d "items_to_add[billing_period_unit][0]=month")
RAMP_ID=$(echo "$RAMP_RESP" | jq -r '.ramp.id // empty')
if [ -n "$RAMP_ID" ]; then
  echo "Ramp created: $RAMP_ID"
else
  echo "$RAMP_RESP" | jq .
fi
echo ""

# 3. Schedule cancellation for Sept 10, 2027 (within 18-month window)
echo "3. Scheduling cancellation for 2027-09-10..."
CANCEL_RESP=$(curl -s -X POST "${BASE_URL}/subscriptions/${SUB_ID}/cancel_for_items" \
  -u "${API_KEY}:" \
  -d "cancel_option=specific_date" \
  -d "cancel_at=${CANCEL_AT}")
if echo "$CANCEL_RESP" | jq -e '.subscription.id' >/dev/null 2>&1; then
  echo "Cancellation scheduled"
else
  echo "Cancel response:"
  echo "$CANCEL_RESP" | jq .
  echo ""
  echo "Trying end_of_term as fallback..."
  CANCEL_RESP2=$(curl -s -X POST "${BASE_URL}/subscriptions/${SUB_ID}/cancel_for_items" \
    -u "${API_KEY}:" \
    -d "cancel_option=end_of_term")
  echo "$CANCEL_RESP2" | jq .
fi
echo ""

echo "=== Setup Complete ==="
echo ""
echo "Subscription ID: $SUB_ID"
echo "Expected cancel date: 2027-09-10"
echo ""
echo "To test Ghost of March in HiveSight:"
echo "  1. Enter Subscription ID: $SUB_ID"
echo "  2. Set Expected Cancel Date: 2027-09-10"
echo "  3. Click 'Validate Ghost of March'"
echo ""

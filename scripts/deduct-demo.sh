#!/usr/bin/env bash
set -euo pipefail

BASE_URL="http://localhost:8081"

# ─── colour helpers ───────────────────────────────────────────────────────────
BOLD="\033[1m"; CYAN="\033[1;36m"; GREEN="\033[1;32m"; YELLOW="\033[1;33m"; RESET="\033[0m"

header() { echo -e "\n${CYAN}${BOLD}=== $* ===${RESET}"; }
info()   { echo -e "${YELLOW}▶ $*${RESET}"; }
ok()     { echo -e "${GREEN}✔ $*${RESET}"; }

# ─── prerequisites ────────────────────────────────────────────────────────────
for cmd in curl python3 jq uuidgen; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: '$cmd' is required but not installed."; exit 1; }
done

# ─── load secrets ─────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../.env"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: .env file not found at $ENV_FILE"; exit 1
fi
set -a; source "$ENV_FILE"; set +a

if [[ -z "${JWT_SECRET:-}" ]]; then
  echo "ERROR: JWT_SECRET is not set in .env"; exit 1
fi

# ─── health check ─────────────────────────────────────────────────────────────
header "Health Check"
HEALTH=$(curl -sf "$BASE_URL/health" || true)
if [[ -z "$HEALTH" ]]; then
  echo "ERROR: Service is not running at $BASE_URL. Start it first with:"
  echo "  set -a; source .env; set +a && ./mvnw spring-boot:run"
  exit 1
fi
ok "Service is up: $HEALTH"

# ─── generate random IDs for this run ─────────────────────────────────────────
CUSTOMER_ID="cust-$(uuidgen | tr '[:upper:]' '[:lower:]' | cut -c1-8)"
ORDER_ID="order-$(uuidgen | tr '[:upper:]' '[:lower:]' | cut -c1-8)"
REQUEST_TS=$(python3 -c "import time; print(int(time.time() * 1000))")
SERVICE_CALLER="order-svc-$(uuidgen | tr '[:upper:]' '[:lower:]' | cut -c1-6)"

info "Customer ID   : $CUSTOMER_ID"
info "Order ID      : $ORDER_ID"
info "Request TS    : $REQUEST_TS"
info "Service caller: $SERVICE_CALLER"

# ─── JWT generation (pure Python, no external deps) ───────────────────────────
JWT_PY='
import hmac, hashlib, base64, json, time, sys

def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

def make_jwt(sub, role, secret):
    header  = b64url(json.dumps({"alg":"HS256","typ":"JWT"}).encode())
    payload = b64url(json.dumps({"sub": sub, "roles": [role],
                                  "iat": int(time.time()),
                                  "exp": int(time.time()) + 86400}).encode())
    signing_input = (header + "." + payload).encode()
    sig = b64url(hmac.new(secret.encode(), signing_input, hashlib.sha256).digest())
    return header + "." + payload + "." + sig

print(make_jwt(sys.argv[1], sys.argv[2], sys.argv[3]))
'

USER_TOKEN=$(python3 -c "$JWT_PY" "$CUSTOMER_ID"    "USER"    "$JWT_SECRET")
SVC_TOKEN=$(python3  -c "$JWT_PY" "$SERVICE_CALLER" "SERVICE" "$JWT_SECRET")
ok "Tokens generated"

# ─── Step 1: Create wallet ────────────────────────────────────────────────────
header "Step 1: Create Wallet  (POST /wallets)"
info "Customer: $CUSTOMER_ID"

CREATE_RESP=$(curl -sf -X POST "$BASE_URL/wallets" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json")

echo "$CREATE_RESP" | jq .
WALLET_ID=$(echo "$CREATE_RESP" | jq -r '.id')
ok "Wallet created — ID: $WALLET_ID"

# ─── Step 2: Top-up ───────────────────────────────────────────────────────────
header "Step 2: Top-Up  (POST /wallets/$WALLET_ID/topup)"
info "Amount: 500.00 INR"

TOPUP_RESP=$(curl -sf -X POST "$BASE_URL/wallets/$WALLET_ID/topup" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"amount": 500.00}')

echo "$TOPUP_RESP" | jq .
BALANCE_AFTER=$(echo "$TOPUP_RESP" | jq -r '.balanceAfter')
ok "Top-up applied — balance: $BALANCE_AFTER INR"

# ─── Step 3: Deduct ───────────────────────────────────────────────────────────
header "Step 3: Deduct  (POST /wallets/$WALLET_ID/deduct)"
info "Order: $ORDER_ID  |  Amount: 100.00 INR  |  Caller: $SERVICE_CALLER"

DEDUCT_BODY=$(cat <<EOF
{
  "orderId": "$ORDER_ID",
  "requestTimestamp": $REQUEST_TS,
  "customerId": "$CUSTOMER_ID",
  "amount": 100.00
}
EOF
)

DEDUCT_RESP=$(curl -sf -X POST "$BASE_URL/wallets/$WALLET_ID/deduct" \
  -H "Authorization: Bearer $SVC_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$DEDUCT_BODY")

echo "$DEDUCT_RESP" | jq .
BALANCE_AFTER=$(echo "$DEDUCT_RESP" | jq -r '.balanceAfter')
ok "Deduction applied — balance after: $BALANCE_AFTER INR"

# ─── Step 4: Idempotency replay ───────────────────────────────────────────────
header "Step 4: Idempotency Replay  (same request, same orderId + requestTimestamp)"
info "Resending identical deduct request — balance must NOT change"

REPLAY_RESP=$(curl -sf -X POST "$BASE_URL/wallets/$WALLET_ID/deduct" \
  -H "Authorization: Bearer $SVC_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$DEDUCT_BODY")

echo "$REPLAY_RESP" | jq .
REPLAY_BALANCE=$(echo "$REPLAY_RESP" | jq -r '.balanceAfter')

if [[ "$REPLAY_BALANCE" == "$BALANCE_AFTER" ]]; then
  ok "Idempotency confirmed — balance still $REPLAY_BALANCE INR (no double-deduction)"
else
  echo "ERROR: Balance changed on replay! Expected $BALANCE_AFTER, got $REPLAY_BALANCE"
  exit 1
fi

# ─── Step 5: Final balance ────────────────────────────────────────────────────
header "Step 5: Final Balance  (GET /wallets/$WALLET_ID/balance)"

BAL_RESP=$(curl -sf "$BASE_URL/wallets/$WALLET_ID/balance" \
  -H "Authorization: Bearer $USER_TOKEN")

echo "$BAL_RESP" | jq .
FINAL_BAL=$(echo "$BAL_RESP" | jq -r '.balance')
ok "Final balance: $FINAL_BAL INR  (expected 400.00)"

# ─── Step 6: Transaction list ─────────────────────────────────────────────────
header "Step 6: Transactions  (GET /wallets/$WALLET_ID/transactions)"

TXN_RESP=$(curl -sf "$BASE_URL/wallets/$WALLET_ID/transactions" \
  -H "Authorization: Bearer $USER_TOKEN")

echo "$TXN_RESP" | jq .
TXN_COUNT=$(echo "$TXN_RESP" | jq '.content | length')
ok "$TXN_COUNT transaction(s) recorded  (TOPUP + DEDUCTION)"

echo -e "\n${GREEN}${BOLD}All steps completed successfully.${RESET}"

# KeyChainWalletService

Keychain Prepaid Wallet Service — a logistics platform wallet that manages customer balances,
records every money movement, and enforces the balance constraint when orders are placed.

## Running locally

```bash
# 1. Copy the env template
cp .env.example .env
```

Edit `.env` and fill in your values. For a default local setup:

> **Note for reviewers:** The values below are provided solely to let you run the service locally without extra setup. In any non-local environment all secrets must come from a secrets manager (e.g. AWS Secrets Manager, HashiCorp Vault) or be injected as environment variables by the deployment platform — never hardcoded or committed to source control.

```
DB_URL=jdbc:postgresql://localhost:5432/walletdb
DB_NAME=walletdb
DB_USERNAME=wallet_user
DB_PASSWORD=wallet_pass
JWT_SECRET=keychain-wallet-service-super-secret-key-minimum-32-chars-hs256
```

```bash
# 2. Export env vars into your shell (repeat this in every new terminal session)
set -a; source .env; set +a

# 3. Start Postgres
docker compose up -d

# 4. Start the service (env vars from step 2 must be exported first)
./mvnw spring-boot:run

# Smoke tests
curl http://localhost:8081/health
curl http://localhost:8081/health/db
```

## Running the end-to-end demo

`scripts/deduct-demo.sh` walks through the full deduct flow in one command: wallet creation → top-up → deduction → idempotency replay → balance check → transaction list. Every run uses freshly generated random IDs so runs never conflict.

**Prerequisites:** `curl`, `python3`, `jq`, `uuidgen` must be installed, and the service must already be running.

```bash
# Terminal 1 — start the service (env vars must be exported first)
set -a; source .env; set +a
./mvnw spring-boot:run

# Terminal 2 — run the demo
set -a; source .env; set +a
bash scripts/deduct-demo.sh
```

The script prints a labelled header and formatted JSON response for each step. It exits non-zero if the idempotency check fails or any `curl` call returns an error HTTP status.

---

## Running tests

```bash
./mvnw test
```

Tests use Testcontainers to spin up a real PostgreSQL 15 container automatically. Docker must be running. The container starts before the first test and is destroyed when the JVM exits. No data persists between runs.

---

## Authentication

Every endpoint except `/health` and `/health/db` requires a bearer token:

```
Authorization: Bearer <HS256-signed JWT>
```

The shared secret is read from the `JWT_SECRET` environment variable (set in `.env`). The service is a stateless OAuth2 resource server — the token is validated on every request; there are no sessions or login endpoints.

### Token types

Two distinct token types are used depending on the caller:

**User token** (frontend):
```json
{ "sub": "cust001", "roles": ["USER"] }
```

**Service token** (internal services, e.g. Order Service):
```json
{ "sub": "order-service", "roles": ["SERVICE"] }
```

### Generating test tokens

Use [jwt.io](https://jwt.io) to mint tokens for Postman or curl testing:

1. Set **Algorithm** to `HS256`.
2. Paste a payload — for a user token:
   ```json
   { "sub": "cust001", "roles": ["USER"] }
   ```
   For a service token:
   ```json
   { "sub": "order-service", "roles": ["SERVICE"] }
   ```
3. In the **Verify Signature** box paste the value of `JWT_SECRET` from your `.env` file.
4. Copy the encoded token from the left panel and use it as `Authorization: Bearer <token>`.

### Endpoint authorization

| Endpoint | Role required | Ownership check |
|---|---|---|
| `POST /wallets` | `USER` | `sub` becomes `customerId` |
| `POST /wallets/{id}/topup` | `USER` | `sub` must equal `wallet.customerId` |
| `POST /wallets/{id}/deduct` | `SERVICE` | None — service is trusted |
| `GET /wallets/{id}/balance` | `USER` or `SERVICE` | USER: `sub` must equal `wallet.customerId`; SERVICE: skipped |
| `GET /wallets/{id}/transactions` | `USER` or `SERVICE` | USER: `sub` must equal `wallet.customerId`; SERVICE: skipped |

A `USER` token presented to `/deduct` returns **403**. A `SERVICE` token presented to `/wallets` (create) or `/topup` returns **403**.

---

## API Reference

### POST /wallets

Creates a new wallet for the authenticated customer.

- **Auth:** `USER` role required. `customerId` is taken from `jwt.sub`.
- **Request body:** none
- **201 Created**

```json
{
  "id": "V1StGXR8_Z5jdHi6B_myT",
  "customerId": "cust001",
  "balance": 0.00,
  "currency": "INR",
  "status": "ACTIVE"
}
```

| Status | Meaning |
|--------|---------|
| 201 | Wallet created |
| 401 | Missing or invalid JWT |
| 409 | A wallet already exists for this `customerId` |

---

### POST /wallets/{id}/topup

Adds funds to a wallet. Only the wallet owner may top up.

- **Auth:** JWT `sub` must match `wallet.customerId`.

> **Idempotency not implemented:** The frontend should supply a `paymentTransactionId` (e.g.
> the gateway transaction reference) with each topup request to act as an idempotency key.
> This field is absent from the current requirements. When added, idempotency can be enforced
> using the existing `idempotency_record` table with `paymentTransactionId` as the key —
> the same pattern used by the deduct endpoint. Until then, duplicate topups from retries or
> double-clicks must be handled via customer support.

- **Request body:**

```json
{ "amount": 500.00 }
```

`amount` must be positive, non-zero, and have at most 2 decimal places.

- **200 OK**

```json
{
  "walletId": "V1StGXR8_Z5jdHi6B_myT",
  "customerId": "cust001",
  "transactionId": "abc123...",
  "amount": 500.00,
  "balanceBefore": 0.00,
  "balanceAfter": 500.00,
  "currency": "INR"
}
```

| Status | Meaning |
|--------|---------|
| 200 | Top-up applied |
| 400 | Wallet is `SUSPENDED`, or validation error on `amount` |
| 401 | Missing or invalid JWT |
| 403 | JWT `sub` ≠ wallet owner |
| 404 | Wallet not found |

---

### POST /wallets/{id}/deduct

Deducts funds from a wallet for an order. Idempotent — replaying the same request with the same `orderId` returns the original cached response without touching the balance again.

- **Auth:** `SERVICE` role required.
- **Request body:**

```json
{
  "orderId": "order789",
  "customerId": "cust001",
  "amount": 100.00
}
```

All three fields are required.

- **200 OK**

```json
{
  "walletId": "V1StGXR8_Z5jdHi6B_myT",
  "customerId": "cust001",
  "transactionId": "xyz987...",
  "orderId": "order789",
  "amount": 100.00,
  "balanceBefore": 500.00,
  "balanceAfter": 400.00,
  "currency": "INR"
}
```

| Status | Meaning |
|--------|---------|
| 200 | Deduction applied (or replay of an already-applied deduction) |
| 400 | Wallet is `SUSPENDED`, or blank required field |
| 401 | Missing or invalid JWT |
| 403 | `request.customerId` ≠ `wallet.customerId` |
| 404 | Wallet not found |
| 422 | Insufficient balance, OR `idempotencyKey` was already used with different parameters |

---

### GET /wallets/{id}/balance

Returns the current balance.

- **Auth:** `USER` or `SERVICE` role required. For `USER` tokens, `jwt.sub` must match `wallet.customerId`. `SERVICE` tokens skip the ownership check.
- **200 OK**

```json
{
  "walletId": "V1StGXR8_Z5jdHi6B_myT",
  "customerId": "cust001",
  "balance": 400.00,
  "currency": "INR"
}
```

| Status | Meaning |
|--------|---------|
| 200 | Balance returned |
| 401 | Missing or invalid JWT |
| 403 | JWT `sub` ≠ wallet owner |
| 404 | Wallet not found |

---

### GET /wallets/{id}/transactions

Returns a cursor-paginated, newest-first list of all transactions for a wallet.

- **Auth:** `USER` or `SERVICE` role required. For `USER` tokens, `jwt.sub` must match `wallet.customerId`. `SERVICE` tokens skip the ownership check.
- **Query params:**
  - `size` (default `10`, max `50`) — number of records per page; values above `50` are silently capped.
  - `nextToken` (optional) — opaque cursor returned by the previous response; omit on the first call.
- **200 OK**

```json
{
  "content": [
    {
      "id": "xyz987...",
      "type": "DEDUCTION",
      "amount": 100.00,
      "balanceBefore": 500.00,
      "balanceAfter": 400.00,
      "status": "SUCCESS",
      "referenceId": "order789",
      "referenceType": "ORDER_DEDUCTION",
      "createdAt": "2024-05-17T09:00:00Z"
    }
  ],
  "size": 1,
  "nextToken": "base64encodedCursor"
}
```

`nextToken` is `null` on the last page. To fetch the next page pass it as `?nextToken=<value>`.

`type` values: `TOPUP`, `DEDUCTION`, `REFUND`, `REVERSAL`.

| Status | Meaning |
|--------|---------|
| 200 | Transaction list returned |
| 400 | `nextToken` is present but malformed |
| 401 | Missing or invalid JWT |
| 403 | JWT `sub` ≠ wallet owner |
| 404 | Wallet not found |

---

## Idempotency

Idempotency applies to the deduct endpoint only.

### Why it exists

The Order Service may retry a deduction after a timeout or network hiccup. Without idempotency, each retry would subtract from the balance a second time. The service uses `orderId` as the idempotency key — deterministic across retries since any retry for the same order carries the same `orderId`.

### Request fingerprint

On every deduct call the service computes:

```
SHA-256( walletId | orderId | customerId | amount )
```

This 64-character hex digest binds the idempotency key to the exact parameters of the original request. Replaying the same key with any parameter changed (different amount, different orderId, etc.) is detected and rejected with a `422`.

### Happy-path flow (first call with a given key)

1. Check `idempotency_record` for the key — **miss**.
2. Acquire `SELECT FOR UPDATE` lock on the wallet row.
3. Re-check `idempotency_record` inside the lock — still a miss.
4. Validate ownership, wallet status, and balance.
5. Subtract balance, write `wallet_transaction` row, write `idempotency_record` with the serialised `DeductResponse` JSON.
6. All three writes commit atomically in one transaction.

### Replay flow (same key, same parameters)

1. Check `idempotency_record` — **hit**.
2. Verify the stored hash matches the new request's hash.
3. Deserialise and return the cached `DeductResponse`. No balance change, no new transaction row.

### Concurrent in-flight retry

This is the subtle case: Request 1 (R1) is mid-flight when Request 2 (R2) arrives with the same key before R1 has committed.

```
R1:  check record (miss) → acquire lock → check record (miss) → deduct → commit
                                           |
R2:  check record (miss) → [blocked on lock] → acquire lock → check record (HIT) → return cached
```

R2's first check misses because R1 has not yet committed. R2 then blocks on `SELECT FOR UPDATE`. Once R1 commits (balance deducted, record written), R2 acquires the lock and re-checks the record — it finds R1's committed record and returns the cached response. The balance is deducted exactly once. No application-level retry or coordination is needed; the database lock is the synchronisation barrier.

### Key mismatch

If the same `idempotencyKey` is presented with different parameters, the stored hash will not match. The service returns `422 Unprocessable Content` with an error body identifying the key. This signals to the caller that the key is already consumed and the new request parameters cannot be honoured under it.

### Failure isolation

If the deduction fails (insufficient balance, suspended wallet, validation error), the entire transaction rolls back — no `idempotency_record` row is written. The caller is free to retry the same key or a fresh one; it will be treated as a brand-new first attempt.

---

## Engineering Decisions

### Why Java?

Static typing catches an entire class of financial bugs at compile time — wrong numeric type,
null balance, missing null-checks — rather than in production. Python and Ruby's dynamic
typing is a liability when every operation must be correct.

Spring Boot's `@Transactional` and Spring JDBC provide battle-tested, declarative transaction
boundaries. Manually managing `BEGIN / COMMIT / ROLLBACK` in Go or Python is where subtle
bugs hide — a partially committed deduction with no ledger entry is the kind of corruption
that is very hard to detect and harder to recover from.

Spring's ecosystem (HikariCP connection pooling, structured exception hierarchy, test slices)
is purpose-built for services that talk to relational databases under load.

- **vs Go** — excellent for high-throughput network services but lacks rich transactional
  middleware. Correct retry/rollback logic written by hand is a source of bugs.
- **vs Python** — fast to prototype but the dynamic type system, GIL, and weaker DB
  transaction libraries make it a poor fit for ACID-correct financial code.
- **vs Rust** — memory safety is compelling but the learning curve, smaller hiring pool, and
  longer development cycle don't pay off for a service whose bottleneck is DB I/O, not CPU.

---

### Why PostgreSQL?

PostgreSQL provides the specific guarantees a ledger service needs:

- **ACID transactions** — `READ COMMITTED` (default) and `SERIALIZABLE` isolation modes are
  well-understood and production-proven for financial workloads.
- **`SELECT FOR UPDATE`** — row-level pessimistic locking is a first-class feature and is
  the mechanism used to enforce the balance constraint under concurrent deductions.
- **`NUMERIC` type** — exact decimal arithmetic. `FLOAT`/`DOUBLE` use binary fractions that
  cannot represent most decimal values exactly — ₹0.10 stored as a float is
  ₹0.09999999..., which is catastrophic in a ledger.
- **`JSONB`** — flexible metadata on transactions without EAV anti-patterns or schema
  migrations for every new context field.

- **vs MySQL** — gap locking behaviour and historically weaker transaction isolation semantics
  make it a worse fit.
- **vs MongoDB / DynamoDB** — NoSQL sacrifices the ACID guarantees a ledger requires. You
  cannot safely enforce "balance never goes negative" without transactions.
- **vs Redis** — in-memory; not durable enough for financial data even with AOF persistence.

---

### Why Pessimistic Locking (`SELECT FOR UPDATE`)?

The balance constraint — a wallet must never go negative — is a hard invariant, not a
best-effort goal.

With `SELECT FOR UPDATE`, the wallet row is locked the moment it is read inside the deduction
transaction. No other transaction can read-then-update the same row until the first commits.
The constraint is enforced at the database level, with no application-level retry loops.

The alternative, optimistic locking (a `version` column with compare-and-swap), would require
the application to catch a conflict, retry, re-validate, and retry again under sustained
concurrent load. That retry loop adds complexity and can still produce incorrect behaviour if
the retry logic has bugs.

The critical section is tiny: read → validate → update wallet → insert transaction row. This
takes microseconds in Postgres. The lock is held for an imperceptible duration, so blocking
is not a concern at the scale of this service.

Topups do **not** use `FOR UPDATE` — two concurrent topups on the same wallet cannot violate
any constraint, so there is no reason to block them.

---

### Why NanoID over BIGSERIAL or UUID?

Wallet and transaction IDs are exposed in every API URL (`/wallets/:id/balance`,
`/wallets/:id/transactions`). Sequential integers are enumerable — an attacker who knows
wallet `1001` exists can probe `1002`, `1003`, ... leaking customer existence and count.

NanoID produces a 21-character URL-safe random string (~126 bits of entropy), the same
collision resistance as UUID v4 but shorter (21 chars vs 36) and cleaner in URLs and logs.

IDs are generated in the application layer before the DB insert. This means the response ID
is known immediately, without waiting for a DB-assigned sequence — useful for idempotency
flows where the caller needs to know the ID it is retrying.

`created_at TIMESTAMPTZ` already provides time-ordering, so a time-sortable ID format (ULID)
adds no benefit here.

Tradeoff accepted: `VARCHAR(21)` index is marginally larger than a `BIGINT` index, but at
wallet-service scale (one row per customer) this is imperceptible.

---

### Why Idempotency Uses a Double-Check Pattern

The deduct flow checks the `idempotency_record` table **twice** — once before acquiring the
row lock and once after.

**First check (fast path):** If the record already exists (a replay), the lock is never
acquired and the cached response is returned immediately, eliminating contention for retries.

**Second check (race-condition guard):** Two concurrent requests (R1, R2) with the same key
can both miss the first check if R1 has not committed yet. R2 then blocks on
`SELECT FOR UPDATE`. Once R1 commits, R2 acquires the lock, finds R1's committed record on the
second check, and returns the cached response — the deduction is not re-applied. Without the
second check, R2 would execute a duplicate deduction.

**SHA-256 request fingerprint:** The fingerprint (`SHA-256(walletId|orderId|customerId|amount)`)
binds the idempotency key to the exact request parameters. Replaying the same key with any
field changed — different amount, different customer — returns `422` rather than silently
deducting a different amount under the same key.

- **vs single pre-lock check** — cannot catch the concurrent in-flight case; R2 slips through
  and deducts twice.
- **vs optimistic locking + retry** — retry loops add complexity and can still produce incorrect
  behavior if the retry logic itself has bugs.

---

### Why Two Distinct JWT Roles (`USER` vs `SERVICE`)

Every token carries a `roles` claim of either `["USER"]` or `["SERVICE"]`. The `/deduct`
endpoint requires `SERVICE`; `/wallets` (create) and `/topup` require `USER`.

**Why not a single role:** The deduct endpoint must be callable by internal services (the order
system) but never directly by a customer. With a single role, a leaked customer token would
allow a user to deduct from their own wallet arbitrarily, bypassing the order flow entirely.
Splitting the roles means a `USER` token on `/deduct` returns `403` at the framework level
before any business logic runs.

**Why SERVICE tokens skip the ownership check:** An internal service does not have a
`customerId` as its JWT `sub` — it acts on behalf of any customer. Ownership is still enforced:
the caller-supplied `customerId` in the request body is validated against `wallet.customerId`.
The check just moves from the token subject to the request payload.

**Why `roles`, not the standard OAuth2 `scope`:** The `scope` claim implies permissions
delegated by an authorization server. This service has no authorization server — tokens are
minted directly with a shared secret. Using `roles` makes the intent explicit: it describes
the type of caller, not a delegated permission scope.

- **vs a single role** — customer tokens become capable of calling service-only endpoints; a
  leaked token has a larger blast radius.
- **vs separate endpoints per caller type** — doubles the route surface area with no additional
  expressiveness.

---

## Schema

Three tables:

**`wallet`** — current state. One row per customer. Balance is denormalized here for O(1)
reads; it is always updated atomically with the corresponding `wallet_transaction` row inside
a single DB transaction.

**`wallet_transaction`** — immutable ledger. Every money movement is a new row. Rows are
never updated or deleted. Reversals are new rows of type `REVERSAL`. `balance_before` and
`balance_after` snapshots make any single row self-auditable without replaying history.

**`idempotency_record`** — deduplication cache for the deduct endpoint. Keyed by
`idempotency_key`. Stores the SHA-256 request fingerprint and the full serialised
`DeductResponse` JSON so replays can return the original response without touching the wallet.
A record is written only when a deduction succeeds; failed attempts leave no trace here.

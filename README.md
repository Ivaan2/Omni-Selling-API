# Order Management API

A Java 21 / Spring Boot 4.1.1 modular monolith for a small ordering workflow. Catalog, Ordering, Payment, Fulfilment and Integration have independent responsibilities. The domain is plain Java; PostgreSQL stores transactions, idempotency records, payment claims and delivery leases.

Implemented: catalog listing, order creation/read/cancellation, safe simulated payment, restricted administrative soft deletion, persistent request idempotency, versioned events, HTTP outbox delivery, and eventual PENDING fulfilment creation. Inventory, refunds, real payment providers, customer authentication and shipping are outside the MVP.

## Prerequisites

- **Full JDK 21**, including `javac` and `lib/ct.sym`; a JRE is insufficient.
- Docker Engine and Docker Compose v2. Docker must be running for integration tests.
- curl; Python 3 for smoke/load scripts; OpenSSL for generating local credentials.
- Maven is downloaded by the Maven Wrapper (3.9.11). Maven Central access is needed on first build.
- TCP ports 5432 (PostgreSQL), 8090 (demo receiver), 8080 (API) available locally.

Spring Boot's dependency management pins Hibernate, Liquibase, Jackson, JUnit and Testcontainers. springdoc 3.1.1 is the maintained Boot 4 line; startup and `/v3/api-docs` are covered by integration tests. There is no Flyway or H2 dependency.

## Start locally

Run all commands from the repository root:

```sh
./scripts/local-env.sh
set -a
. ./.env
set +a
docker compose -f docker/docker-compose.yml up -d --wait
./mvnw spring-boot:run
```

The helper creates an ignored, mode-600 `.env` containing the local database password `orders` and a random administrative password. It preserves any existing `.env`. Keep it private. Use your secret manager for deployment. Compose reads `.env`; Java requires these variables exported as above. Existing `.env` values are preserved. `DATABASE_PASSWORD` optionally overrides the local `orders` default; `ADMIN_PASSWORD` remains required. On an existing PostgreSQL volume, changing `.env` does not change the database user's password.

Liquibase applies the ordered SQL changesets on a clean database. Hibernate uses `ddl-auto=validate`; it never creates the schema. Missing administrative credentials or unavailable PostgreSQL prevent startup. The catalog seeds three fixed products, two commercially available.

```sh
curl --fail http://localhost:8080/actuator/health/liveness
curl --fail http://localhost:8080/actuator/health/readiness
# Separate terminal, same exported .env:
python3 scripts/smoke.py
```

Swagger UI: `http://localhost:8080/swagger-ui/index.html`. OpenAPI JSON: `http://localhost:8080/v3/api-docs`. These are local developer addresses, not hosted previews. Disable docs for production using `SPRINGDOC_API_DOCS_ENABLED=false` and `SPRINGDOC_SWAGGER_UI_ENABLED=false`.

## Executable complete order/payment flow

Use a fresh idempotency key for each independent operation. Reusing these example keys deliberately replays the same operation within the retention window.

```sh
curl --fail http://localhost:8080/api/v1/products

CREATE_RESPONSE=$(curl --fail -sS -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-order-001' \
  -H 'X-Correlation-ID: demo-flow-001' \
  -d '{"customerId":"customer-123","items":[{"productId":"00000000-0000-4000-8000-000000000001","quantity":2}]}')
ORDER_ID=$(printf '%s' "$CREATE_RESPONSE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
printf '%s\n' "$CREATE_RESPONSE"

curl --fail -X POST "http://localhost:8080/api/v1/orders/$ORDER_ID/pay" \
  -H 'Idempotency-Key: demo-payment-001'
curl --fail "http://localhost:8080/api/v1/orders/$ORDER_ID"
```

Creation returns **201** with `Location`. Payment uses the stored total; no request body or client amount is accepted. Default fake payment succeeds; fulfilment eventually appears in **PENDING**. Paid orders cannot be cancelled or deleted. Cancellation returns 200; it is a state transition, so repeating it returns 409.

```sh
CANCEL_RESPONSE=$(curl --fail -sS -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: cancel-example-001' \
  -d '{"customerId":"customer-123","items":[{"productId":"00000000-0000-4000-8000-000000000002","quantity":1}]}')
CANCEL_ID=$(printf '%s' "$CANCEL_RESPONSE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
curl --fail -X POST "http://localhost:8080/api/v1/orders/$CANCEL_ID/cancel"

# Administrative operations return 200 with no body; repeats are idempotent.
curl --fail -X PATCH "http://localhost:8080/internal/api/v1/orders/$CANCEL_ID" \
  -u "$ADMIN_USERNAME:$ADMIN_PASSWORD"
curl --fail -X PATCH http://localhost:8080/internal/api/v1/products/00000000-0000-4000-8000-000000000003 \
  -u "$ADMIN_USERNAME:$ADMIN_PASSWORD"
```

Internal paths require HTTP Basic with ADMIN role. `/internal` is not itself a security boundary. Public demo endpoints have no customer authentication: `customerId` is an **unverified identifier**, not an authorization boundary. Production requires TLS, network restrictions, real customer authentication and ownership checks. Metrics require ADMIN; public health responses omit details.

## Demonstrate reliable event delivery

With the helper's receiver URL, every order transition creates an independent HTTP delivery. `OrderPaid` also creates an independent internal delivery. A 2xx receiver response is the positive acknowledgement contract. The receiver must durably commit its own work **before** returning 2xx. The supplied receiver is a lightweight demo, with in-memory deduplication only.

```sh
docker compose -f docker/docker-compose.yml logs --tail=30 receiver
# Receiver outage: business commits still succeed.
docker compose -f docker/docker-compose.yml stop receiver
# Create/pay a fresh order with fresh keys; inspect order and eventual fulfilment.
docker compose -f docker/docker-compose.yml exec -T postgres psql -U orders -d orders -c \
  "SELECT destination,status,retry_count,last_error FROM outbox_deliveries ORDER BY created_at;"
docker compose -f docker/docker-compose.yml start receiver
# Observe retries in receiver logs and DELIVERED state in PostgreSQL.
```

Automatic retries use bounded exponential delays and eventually become EXHAUSTED; the event is retained. After fixing the receiver, explicitly requeue a selected exhausted event:

```sh
EVENT_ID='<event-uuid>'
docker compose -f docker/docker-compose.yml exec -T postgres psql -U orders -d orders -v event_id="$EVENT_ID" \
  < scripts/recover-deliveries.sql
```

Leases and claim tokens allow multiple workers and crash recovery. Delivery is **at least once**, with no global or per-order delivery ordering guarantee. Consumers must deduplicate by eventId and handle aggregateVersion (see architecture). Fulfilment is independent of external receiver availability and never advances beyond PENDING automatically.

## Payment failure, unknown outcome and recovery

There are no public outcome-control query parameters. Simulator behavior is explicit environment configuration:

- `FAKE_PAYMENT_OUTCOME=FAILED`: definitive decline. A new key may start another attempt after failure.
- `FAKE_PAYMENT_OUTCOME=UNKNOWN`: ambiguous result. The order remains CREATED, payment UNKNOWN. New distinct charges, cancellation and deletion are blocked.
- Restart with `FAKE_PAYMENT_LOOKUP_OUTCOME=SUCCEEDED` (or FAILED) to resolve existing UNKNOWN fake operations through lookup. Keep the same database volume. The recovery worker reconciles old attempts using their stable provider references.

Default recovery eligibility is 30 seconds; bounded batches run every five seconds. A crash between durable acquisition and local confirmation is recoverable: lookup first, and safe stable-key replay only when the adapter explicitly supports it. Fake operations are persisted in `simulated_provider_operations`; this is a **simulation**, not production payment infrastructure. A real provider must supply its own idempotency, lookup, reconciliation and operational procedures.

A completed idempotent response remains the **original response snapshot**, even if later recovery changes UNKNOWN to PAID. GET the order to see current state. Request-level retention defaults to 24h; permanent successful/active payment rules survive expiration. Processing records are never expired away. Completed expired records are replaced lazily on reuse; production needs scheduled bounded cleanup/archival.

## Configuration

| Variable | Default / requirement |
|---|---|
| DATABASE_URL | `jdbc:postgresql://localhost:5432/orders` |
| DATABASE_USER | `orders` |
| DATABASE_PASSWORD | `orders` for local use; optional override |
| DATABASE_POOL_SIZE | 16, per instance |
| ADMIN_USERNAME / ADMIN_PASSWORD | `admin` / required |
| IDEMPOTENCY_RETENTION | `24h` |
| PAYMENT_ADAPTER | `fake` only |
| FAKE_PAYMENT_OUTCOME | `SUCCEEDED` |
| FAKE_PAYMENT_LOOKUP_OUTCOME | `UNKNOWN` (no forced resolution) |
| PAYMENT_RECOVERY_AGE / PAYMENT_RECOVERY_INTERVAL | `30s` / `5000` ms |
| EVENT_RECEIVER_URL | Empty disables **new** external delivery registration |
| EVENT_HTTP_TIMEOUT | `5s`, connect and request timeout |
| OUTBOX_ENABLED | `true`; local worker control (both scheduled jobs) |
| OUTBOX_POLL_INTERVAL / OUTBOX_BATCH_SIZE | `500` ms / 20 |
| OUTBOX_LEASE | `3m`; must exceed batch size × HTTP timeout |
| OUTBOX_MAX_RETRIES | 8 failed automatic delivery attempts |
| OUTBOX_RETRY_BASE / OUTBOX_RETRY_MAX | `1s` / `5m` |
| LOGGING_LEVEL_ROOT | Spring logging override, default INFO |
| LOGGING_STRUCTURED_FORMAT_CONSOLE | `logstash` |

Durations use Spring notation (`30s`, `3m`, `24h`). PostgreSQL UUID, TIMESTAMPTZ and NUMERIC(19,2) are used. Money rejects rounding loss, negatives and unsupported currencies; item multiplication/addition is exact. EUR only; zero-priced products are allowed. Duplicate product IDs are rejected; item order is ignored by canonical hashing. Customer IDs are case-sensitive and preserved exactly.

## Build and tests

```sh
./mvnw clean verify              # unit + architecture + real PostgreSQL integration tests
./mvnw test                      # unit/application/architecture only
./mvnw -Dit.test=OrderManagementIT verify
java -jar target/order-management-1.0.0-SNAPSHOT.jar
# Optional container image; tests belong to the verify step above.
docker build -f docker/Dockerfile -t order-management:local .
```

`docker/Dockerfile` builds a Java 21 executable jar and runs as a non-root user. Supply environment variables and a reachable DATABASE_URL/EVENT_RECEIVER_URL when running it. Container loopback refers to the container itself, not the host.

Testcontainers is mandatory for the integration suite; no tests are silently skipped when Docker is unavailable. See [TESTING](docs/TESTING.md) for actual execution evidence and concurrency coverage. `scripts/load.py` provides an optional lightweight smoke workload; no 500,000/day throughput claim is made.

## Error mapping and limits

| Status | Meaning |
|---|---|
| 400 | Malformed HTTP/UUID/JSON, bean validation, missing/invalid key, unexpected payment body |
| 401 / 403 | Administrative authentication / authorization failure |
| 404 | Missing resource or publicly hidden deleted order |
| 409 | Invalid lifecycle, pending/unknown charge, idempotency-content conflict, operation in progress, optimistic/database concurrency conflict |
| 422 | Domain input/commercial invariant, duplicate or unavailable products |
| 500 | Unexpected failure; no internal detail returned |
| 503 | Persistence/transaction connection unavailable |

Errors use RFC 9457 Problem Details. Conflicts/unavailability include `Retry-After: 2`; state conflicts need a changed prerequisite, not blind retries. Database constraints are a final invariant guard; caller input should ordinarily fail before reaching them. Replayed creation returns 201 and the original Location/body. Keys are scoped by customer and operation (payment operation includes orderId).

Known production gaps: customer security, real provider integration, hardened receiver authentication/TLS, backup/DR, operational alerting and retention automation. Polling is sequential within a bounded batch; tune throughput against measured lag. No load capacity has been proven. See [ARCHITECTURE](docs/ARCHITECTURE.md), [DECISIONS](docs/DECISIONS.md) and [ASSUMPTIONS](docs/ASSUMPTIONS.md).

## Codex cloud setup

The prepared cloud configuration saves `scripts/cloud-setup.sh` as the installation entry point and explicit startup instructions. Source `scripts/cloud-env.sh` before Maven/Java commands in this machine; it activates the complete JDK 21 and supported proxy/CA configuration. Use the existing isolated checkout rather than creating a worktree.

Ordinary networks can build the Dockerfile directly. A restricted build network can supply optional BuildKit secrets `maven-settings` and `java-truststore`; these are not copied into image layers. In this specific cloud machine, `./scripts/cloud-docker-build.sh` also resolves the platform proxy hostname for the Docker build network and uses a writable Docker CLI configuration directory. It retains TLS verification. `.dockerignore` excludes local credential files.

Saved environment instructions are a draft, not publication. Review/save the settings and publish the prepared environment through the environment UI to make the snapshot reusable. Live API/database/receiver processes must restart in future tasks; generated local credentials are private development configuration, not production secrets.

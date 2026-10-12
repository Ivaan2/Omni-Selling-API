# Testing and executed evidence

## Strategy

Pure domain tests run quickly without Spring/database. Application tests mock outbound ports and check real business outcomes, price resolution, transaction/provider separation and safety boundaries. ArchUnit enforces dependency direction. PostgreSQL-specific integration and REST/security tests use one real PostgreSQL 17.6 Testcontainer, never H2. A separate live jar/Compose smoke exercises real HTTP and the asynchronous scheduled workers.

## Unit/application/architecture coverage

- Money precision, non-negative EUR amounts, exact total calculations.
- Order non-empty/positive/unique-product items, immutable price snapshots, terminal states, mismatched payment amounts and deleted/blocked operations.
- Product unavailable/deleted purchase guards and historical snapshots after deletion.
- Payment success, definitive failure and repeat attempts/history, PENDING/UNKNOWN exclusion and reconciliation, terminal success, immutable attempt records.
- Fulfilment one-step progression and rejection of invalid transitions.
- Order use cases resolve catalog prices, persist transition events, replay original responses without re-executing, and respect payment guards.
- Payment orchestration commits acquisition before provider processing, passes the stored amount outside the transaction, persists ambiguous failures, rejects cancelled orders before invoking a provider, and never replays an unsupported non-idempotent provider operation.
- Core packages cannot depend on Spring, JPA, servlet, infrastructure/configuration; domain cannot depend on application.

## PostgreSQL / API coverage

34 integration test methods exercise:

- Three Liquibase changesets on a clean PostgreSQL database and Hibernate schema validation; UUIDv7/native UUID and exact monetary persistence.
- A deliberately interleaved GET/payment verifies one consistent read snapshot rather than mixed Order/Payment states.
- Shared catalog row locking protects in-flight purchase snapshots from concurrent administrative product deletion.
- FK/unique/check guards, including positive quantities and one unresolved attempt/payment.
- Explicit stale Order/Payment version rejection and JPA Fulfilment optimistic-lock behavior.
- Transaction rollback of order, items, idempotency, event and deliveries; a deliberately rejected event insert also rolls back business creation.
- Durable creation/payment key replay and payload conflict; expired request keys cannot bypass permanent successful-payment constraints.
- Two overlapping payment keys: provider is held outside the transaction using latches while competing pay/cancel/delete operations run. Only one attempt/provider operation succeeds; in-flight same-key use returns processing conflict.
- Barrier-started payment versus cancellation/deletion: exactly one permissible result, never paid+cancelled or paid+deleted.
- Unknown results block distinct charges and reconcile without another attempt; definitive failure permits a later attempt; transport exceptions become UNKNOWN.
- Persisted attempt/provider operation survives simulated interruption between provider commit and local result persistence. **New provider and service instances** reconstruct state and recover using the original operation reference. This test is reconstruction/recovery, not a separate JVM or real-provider test.
- Independent external receiver outage and internal PENDING fulfilment; duplicate paid deliveries create only one fulfilment.
- Retry after HTTP 503, stable original event bytes/IDs, positive 2xx acknowledgement, visible EXHAUSTED state and explicit requeue.
- Concurrent outbox workers claim disjoint live leases; expired leases can be reclaimed; stale token acknowledgement/failure cannot overwrite the new owner.
- Internal fulfilment initialization and acknowledgement roll back together on an injected failure.
- Immutable event snapshots match the committed transition; PostgreSQL rejects event updates.
- All seven routes, validation/missing keys, Problem Details, location/correlation headers, administrative 401/403/200, logical deletion, no client price/amount input, readiness and restricted metrics.
- OpenAPI/Swagger startup, seven operations, response examples and required payment key; payment has no documented amount request body.

## Execution commands

```sh
./mvnw clean verify
./mvnw test
./mvnw -Dit.test=OrderManagementIT verify
# After starting Compose and the application:
python3 scripts/smoke.py
python3 scripts/load.py --requests 100 --concurrency 4
```

Surefire executes `*Test`; Failsafe executes `*IT` during verify. Reports are generated in `target/surefire-reports` and `target/failsafe-reports`; clean verify removes stale reports. If Docker is unavailable, integration execution fails rather than being silently disabled. Unit-only `test` is explicitly not a full acceptance check.

For this Codex machine only, source `scripts/cloud-env.sh` first to select the full JDK 21, writable Maven cache, proxy settings and trusted CA store. `scripts/cloud-setup.sh` exercises that setup and runs clean verify. Nothing disables TLS/checksum verification. The Maven Wrapper distribution has a SHA-256 pin derived from an official SHA-512-verified artifact.

## Actually validated (2026-10-12)

| Check | Executed outcome |
|---|---|
| `./mvnw clean verify` with cloud activation | **PASS**: 61 tests; 27 unit/application/architecture + 34 PostgreSQL integration; 0 failures, 0 errors, 0 skipped |
| Domain | 17 passed |
| Order application | 4 passed |
| Payment application | 5 passed |
| Architecture | 1 passed |
| PostgreSQL integration/API | 34 passed |
| `docker compose up -d --wait` | PostgreSQL 17.6 and Python receiver started; database health checked |
| Executable jar startup | Liquibase applies/validates schema, Hibernate validate succeeds, readiness UP |
| `python3 scripts/smoke.py` | PASS across all seven endpoints, paid order, PENDING fulfilment, idempotency and restricted deletion |
| Full JVM restart with retained Compose data | Earlier PAID order and its PENDING fulfilment remain readable; new jar applies appended changeset without editing previous migrations; smoke passes again |
| Receiver stop/restart | PASS: live smoke passes while receiver is stopped, 5 independent HTTP deliveries remain PENDING, and all recover to DELIVERED after restart |
| Docker multi-stage image build/runtime | PASS with optional BuildKit proxy/CA secrets and cloud proxy hostname mapping; image starts against Compose PostgreSQL, readiness UP, seven-endpoint smoke PASS, runtime uid 999 (non-root) |

No unit/integration tests were left unrun in this environment. Initial setup/test failures were investigated and fixed; only the final completed suite is counted as passing.

## Lightweight performance smoke

Actual recorded output: [performance-smoke.json](performance-smoke.json). A local Java 21 jar with PostgreSQL/receiver in Docker on Linux ran **100 create-order requests at concurrency 4**, with 0 errors, approximately 132.7 achieved requests/second, median 26.9ms, p95 49.8ms and p99 55.6ms. These are finite closed-loop observations from a shared cloud machine (Docker reports roughly 34GB host memory; resource isolation/CPU allocation not benchmark-controlled).

The script reports actual duration/rate/errors/latencies. It does not impose a precise offered rate or simulate daily volume. No warmup, sustained burst, payment load, long-lived database/index growth, production network, multi-replica capacity or downstream fanout was benchmarked. This result does **not** prove 500,000/day readiness; architecture rates are planning estimates. Re-run with a controlled environment and realistic mixed workload before capacity decisions.

## Remaining risks / unexecuted production checks

Real provider/idempotency/reconciliation APIs, durable external consumer side effects, production identity/authorization, TLS/receiver authentication, backups/restore/failover, migration operations on production-sized tables, retention/archival, prolonged saturation, slow/unavailable multi-consumer workloads and full deployment shutdown/failure drills are outside demonstrated guarantees. Exactly-once end-to-end delivery is not claimed.

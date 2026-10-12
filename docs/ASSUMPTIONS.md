# Assumptions, limits and production gaps

## Explicit defaults

- Single deployable modular monolith, one authoritative PostgreSQL database, Java 21 and Spring Boot 4.1.1. Maven Central availability determines the stable patch; compatibility is exercised by actual application/test startup.
- EUR only; exact two-decimal amounts, no implicit rounding, taxes/shipping/discounts absent. Zero-priced products are allowed.
- Catalog purchase resolution holds a shared product-row lock through order commit, so a concurrent product deletion cannot invalidate the in-flight snapshot.
- The small catalog includes non-deleted unavailable products with `available=false`; only commercially available products can be purchased.
- Duplicate product IDs are rejected. Canonical request hashing ignores item order and raw JSON formatting; exact case-sensitive customer identifiers are preserved, not trimmed/normalized into an identity.
- Fractional JSON quantities are rejected rather than silently truncated by Jackson.
- HTTP request limits: 1–100 item lines, quantity 1–1,000,000, customer IDs ≤128 characters, keys 1–200 characters. Database/domain checks also protect internal use cases.
- One logical payment/order; multiple attempts only after definitive failure. No amount/payment outcome is accepted from public clients. Success is terminal; unknown charges block cancellation/deletion/new distinct charges.
- The simulated provider's operation ledger is PostgreSQL-backed solely to demonstrate restart recovery and stable-key retries. It is not a real provider, shared payment platform, or proof of real-provider durability.
- Fake UNKNOWN resolution is explicit deployment/test configuration, never a public query parameter. Recovery is bounded per poll, not limited to a finite reconciliation lifetime; unresolved obligations remain blocked rather than being falsely marked failed.
- Customer IDs are demo labels. They do not prove identity/ownership or constitute a security boundary. ADMIN uses required environment-configured Basic credentials; no committed default password.
- Administrative PATCH requests mean soft deletion and have no body. Repeating deletion returns 200/no body and emits no second deletion event. No public/internal fulfilment management API exists.
- Cancellation is a commercial transition, not idempotent: a repeat returns 409. Request idempotency is required only for creation/payment.
- Creation replay returns 201 and the original body/Location, even after subsequent order payment/deletion. Completed payment replay retains the original response, including an earlier UNKNOWN; clients GET current state.
- Request key retention is configurable, default 24h. Completed expired keys are replaced lazily when reused; processing keys never expire away. A successful logical Payment permanently blocks a second charge independently of request keys.
- The receiver contract acknowledges with any 2xx; it must commit its own durable processing before acknowledgement. The sample Python receiver uses in-memory deduplication and is demonstration infrastructure only.
- HTTP integration is optional; when absent, no new HTTP delivery is registered. Already stored delivery records are preserved. Internal OrderPaid delivery is always registered.
- Outbox delivery is at least once, and multiple workers may reorder an aggregate's events. Consumers must deduplicate; version-aware snapshot projections or explicit buffering/ordered transport serve different needs.
- Fulfilment initializes PENDING and stays there. Other transition rules exist/test at domain level without a warehouse simulator.

## Out of scope

Carts, wishlists, customer profiles, stock/reservation, refunds/returns, real providers, shipping/tracking, general product admin, external authentication platforms, ERP/DW/Martech integrations, Kafka/RabbitMQ/Redis/Kubernetes, event sourcing, CQRS frameworks and distributed transactions. Inventory evolution is documented, with no unused inventory port.

## Implemented guarantees versus deployment requirements

Implemented and tested: local order/outbox atomicity, backend-resolved immutable amounts, persistent atomic idempotency, one unresolved payment attempt, short durable attempt claims before provider I/O, cancellation/deletion races, optimistic version rejection, original event snapshots, lease-token ownership, retries/exhausted preservation, duplicate-safe transactional internal fulfilment.

Not proven: 500,000/day capacity or large bursts, exactly-once external effects, disaster recovery, durable consumer behavior outside this application, provider-specific financial reconciliation, legal compliance, production security maturity. A small load smoke is not capacity certification.

Required before production: TLS/reverse-proxy and network controls, authenticated customers/ownership, hardened admin credential management, receiver authentication and allowed-destination review, real payment adapter procedures, migration credentials separated from application DML credentials, PostgreSQL backups/PITR/restore drills/HA, metrics/alerts/SLOs, unresolved-payment escalation, exhaustion/requeue runbooks, retention/archival/PII minimization and operational load tests.

## Deliberate limits / improvements

- Short pessimistic order locks complement optimistic versions to serialize local payment/cancel/delete races; they never span provider calls. Benchmark hot-order lock waits before optimizing.
- The dispatcher is sequential per bounded batch; a slow receiver can delay processing other deliveries in that batch, although failure never gates internal delivery and each destination retains independent retry state. More replicas or measured bounded parallelism can increase throughput.
- Completed key cleanup, archive jobs, exhausted-event alerting and API ingress backpressure are documented production work, not silently claimed implementations.
- Connection pool size must be budgeted across replicas; the default 16 is not an unlimited horizontal-scaling recommendation.
- Correlation IDs are bounded user-supplied demo identifiers, not trusted security metadata. Event payloads carry them; credentials and complete request bodies are not logged.
- Outbox schema is immutable after insertion (database update trigger). Archival may remove old facts only under a separately approved retention/replay policy.
- FK/CHECK/UNIQUE constraints complement domain checks, but cross-row monetary reconciliation is enforced by aggregate construction and transactional orchestration, not a general database assertion over arbitrary SQL writes.
- Deployment image tags need a regular patching/pinning policy and SBOM/dependency/security review; no supply-chain/production hardening certification is claimed.

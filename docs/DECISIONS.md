# Architecture decisions

All entries describe the implemented MVP except ADR-012, which is explicitly future design. Alternatives are evaluated in context, not claimed universally inferior.

## ADR-001: Modular Monolith

**Context:** Five cohesive capabilities need reliable local transactions at a modest average workload.

**Decision:** One deployable JVM with explicit modules.

**Alternatives:** Microservices and a conventional unmodularized monolith.

**Benefits:** Simple deployment, local transactional correctness, no distributed coordination.

**Costs:** Shared release/database lifecycle; boundaries need discipline.

**Revisit when:** Independent ownership, sustained resource isolation or measured scaling needs outweigh distributed operational costs.

## ADR-002: Hexagonal Architecture

**Context:** Business invariants must remain independent of HTTP, Spring and PostgreSQL.

**Decision:** Pure domain, application orchestration/outbound ports, explicit adapters and composition.

**Alternatives:** Conventional controller-service-entity layering.

**Benefits:** Domain unit testing, replaceable boundaries, architecture checks.

**Costs:** Mapping and port code; concrete application services remain module-facing APIs rather than creating an interface for every class.

**Revisit when:** A much simpler one-off service may not justify these boundaries; adapt granularity, retain dependency direction.

## ADR-003: PostgreSQL

**Context:** Payments, idempotency and delivery acquisition require constraints and transactions.

**Decision:** Authoritative PostgreSQL with UUID, NUMERIC, TIMESTAMPTZ, JSONB and SKIP LOCKED.

**Alternatives:** Document/key-value databases and separate queue infrastructure.

**Benefits:** Atomic business/outbox changes and database-enforced uniqueness.

**Costs:** Connection, vacuum, index and retention capacity require operational work.

**Revisit when:** Measured access patterns or specialized analytics justify an additional datastore; OLTP consistency remains authoritative.

## ADR-004: Liquibase

**Context:** Schema must be versioned and independently validated by Hibernate.

**Decision:** YAML master and ordered formatted SQL changesets; ddl-auto=validate.

**Alternatives:** Flyway (explicitly prohibited), generated schema and ad-hoc SQL.

**Benefits:** Auditable schema and deterministic seed catalog.

**Costs:** Migration compatibility and production locks need review.

**Revisit when:** Revise deployment/migration strategy for large online changes; do not edit applied changesets.

## ADR-005: Order Aggregate

**Context:** Historical amounts and commercial states must be protected.

**Decision:** Order owns immutable OrderItems, Money total and explicit CREATED/PAID/CANCELLED transitions.

**Alternatives:** Anemic entity/service model and separate mutable item management.

**Benefits:** Server-resolved immutable snapshots and one invariant boundary.

**Costs:** Hydrating all items costs memory/queries; HTTP limits bound order size.

**Revisit when:** Very large orders or new business invariants require revisiting aggregate size, not weakening snapshots.

## ADR-006: Payment Port and Adapters

**Context:** Provider I/O must not leak SDKs or execute inside database transactions.

**Decision:** PaymentProcessorPort process/lookup and a deterministic durable FakePaymentAdapter.

**Alternatives:** Direct SDK calls from controllers or transaction-bound services.

**Benefits:** Testable outcomes, stable-key recovery, future provider replaceability.

**Costs:** Simulation is not real payment infrastructure; real adapters need explicit safe-replay contracts.

**Revisit when:** A selected real provider can implement the port with its idempotency/lookup guarantees and operational reconciliation.

## ADR-007: Event-Driven Fulfilment

**Context:** Fulfilment should initialize reliably without gating payment on logistics.

**Decision:** OrderPaid internal outbox consumer inserts independent PENDING fulfilment and acknowledges transactionally.

**Alternatives:** Synchronous creation in payment service and shared Order logistics state.

**Benefits:** Independent lifecycle, duplicate suppression, external receiver isolation.

**Costs:** Eventual consistency; GET may initially lack fulfilment.

**Revisit when:** A real logistics module adds its own transitions/consumers; independent deployment needs justified ownership/scale.

## ADR-008: Limited Commercial Scope

**Context:** Assessment prioritizes transactional correctness over feature breadth.

**Decision:** Only catalog, order, payment, cancellation, restricted deletion and fulfilment initialization.

**Alternatives:** Cart, inventory, refunds, shipping simulators, identity platform.

**Benefits:** Reviewable implementation and focused reliability tests.

**Costs:** Incomplete commerce platform by design.

**Revisit when:** Add capabilities only with explicit business requirements and corresponding invariants.

## ADR-009: Independent Payment Aggregate

**Context:** Payment attempts can fail or remain uncertain while commercial order stays CREATED.

**Decision:** One Payment/order with independent attempts/history and stable operation references.

**Alternatives:** Embedding payment flags inside Order.

**Benefits:** Uncertainty and safe retry become explicit; provider logic stays cohesive.

**Costs:** Application orchestration touches two aggregates in local transactions.

**Revisit when:** New multiple-obligation/refund requirements require an explicit model revision; do not casually relax uniqueness.

## ADR-010: No Paid Order Returns

**Context:** The MVP has no refund provider or return lifecycle.

**Decision:** PAID is terminal and cannot be cancelled or deleted.

**Alternatives:** Treating refunds/returns as cancellation.

**Benefits:** No implied money reversal or invalid terminal transitions.

**Costs:** Customer returns are unsupported.

**Revisit when:** Introduce an independent explicit refund/return model when that feature is authorized.

## ADR-011: Catalog Without Inventory

**Context:** Commercial availability and physical stock are different concerns.

**Decision:** Product availability/deletion controls purchase; no inventory tables or unused ports.

**Alternatives:** Stock counters/reservation placeholders in Catalog.

**Benefits:** Clear boundaries and no false stock-consistency claims.

**Costs:** Purchases do not check stock.

**Revisit when:** A real stock requirement introduces Inventory with its own concurrency/expiry semantics.

## ADR-012: Future Pre-Payment Stock Reservation

**Context:** Future stock must be protected before charging.

**Decision:** Document reserve→pay→confirm/release, initially using short pessimistic stock locks.

**Alternatives:** Charging before reserving, or unchecked reads followed by decrements.

**Benefits:** Protects scarce-stock invariants and supports explicit compensation.

**Costs:** Not implemented; expiry and UNKNOWN payments need coordinated policies.

**Revisit when:** Use conditional atomic stock updates after measured contention warrants optimization.

## ADR-013: Mandatory Idempotency

**Context:** Network retries and concurrent clients must not create duplicate orders/charges.

**Decision:** Persistent atomic uniqueness, canonical SHA-256, PROCESSING conflicts and original response snapshots; 24h configurable retention.

**Alternatives:** In-memory keys, raw JSON hashes, find-then-insert without constraints.

**Benefits:** Cross-instance and restart safety; equivalent item order replays.

**Costs:** Finite retention; cleanup is lazy; unverified customer scope is not authorization.

**Revisit when:** Production needs cleanup, trusted identity scoping and monitoring; business payment safety outlives key retention.

## ADR-014: Transactional Outbox

**Context:** Committed changes must survive unavailable receivers and publisher crashes.

**Decision:** Immutable JSONB events + per-destination delivery rows in the business transaction; leases/tokens, backoff and exhausted state.

**Alternatives:** Direct publication after commit or publication inside a database transaction.

**Benefits:** Atomic local intent, recoverability, independent internal/external progress.

**Costs:** At-least-once duplicates, backlog growth and polling overhead.

**Revisit when:** CDC/broker may replace polling after measured scale/latency and operational readiness justify it.

## ADR-015: Event-Carried State Transfer

**Context:** Downstream consumers should understand facts without querying mutable Order state.

**Decision:** Versioned self-contained envelope with purchase snapshots, eventId and aggregateVersion.

**Alternatives:** ID-only notifications and JPA entity serialization.

**Benefits:** Replayable original facts and reduced availability coupling.

**Costs:** Larger payloads, privacy/retention and schema compatibility responsibilities.

**Revisit when:** Consumer-specific projections/minimization or genuinely huge payloads justify adjusted schemas, not mutable reconstruction.

## ADR-016: UUID and Indexing

**Context:** Cross-instance identifiers and growing append-heavy tables need consistent storage.

**Decision:** Maintained UUIDv7 generator, native UUID and access-pattern/partial indexes.

**Alternatives:** Custom UUIDv7, random UUIDv4 everywhere, index-every-column and premature partitioning.

**Benefits:** Time-oriented identifiers and useful uniqueness/lease lookup indexes.

**Costs:** Index/storage growth still matters; UUIDv7 is not a security token.

**Revisit when:** Measured EXPLAIN/pg_stat results and retention scale justify index/partition/archive changes.

## ADR-017: Concurrency Strategy

**Context:** Optimistic locking alone cannot guard an external charge or deletion race.

**Decision:** JPA @Version plus explicit version checks; short order-row locks for local acquisition/cancel/delete/result transactions, partial active-attempt uniqueness; no lock during external I/O.

**Alternatives:** JVM locks, optimistic-only charge acquisition, pessimistic locks spanning provider calls.

**Benefits:** Cross-instance durable ownership and deterministic race protection; stale state still rejected.

**Costs:** Short lock contention for a hot order; waiting requests may conflict after seeing current state.

**Revisit when:** Measure contention before conditional-update optimization; never blindly retry external operations.

## ADR-018: Seven REST Endpoints

**Context:** The scope specifies five public workflows and two administration operations.

**Decision:** Spring MVC REST with validation, RFC 9457 problems, OpenAPI and Basic ADMIN security.

**Alternatives:** WebFlux and broader admin/fulfilment APIs.

**Benefits:** Readable imperative orchestration and an explicitly small surface.

**Costs:** Blocking request threads during fake provider processing; public customer IDs are unverified.

**Revisit when:** Measured connection/thread bottlenecks or a genuinely reactive dependency chain may justify reconsidering MVC.

## ADR-019: Timestamp-Based Soft Deletion

**Context:** Historical order and payment data must survive logical deletion.

**Decision:** deleted_at for Product/Order, explicit public filtering and idempotent administrative use cases.

**Alternatives:** Physical deletes, DELETED status, global Hibernate filters.

**Benefits:** Audit records survive; administrative validation can still see deleted rows.

**Costs:** Every public query must deliberately enforce visibility; retention is independent.

**Revisit when:** Add archival/audit access requirements with explicit queries, not accidental global hiding.

## ADR-020: Restricted Order Soft Deletion

**Context:** Deleting paid or unresolved obligations would hide actionable financial state.

**Decision:** Only CREATED/CANCELLED without PENDING/UNKNOWN; first deletion appends exactly one logical OrderDeleted.

**Alternatives:** Deleting any order, cascades, or emitting an event on every repeat.

**Benefits:** Preserves financial state and all child/outbox histories.

**Costs:** Deletion does not reclaim storage or satisfy privacy obligations by itself.

**Revisit when:** Legal erasure/retention requires a separate reviewed minimization/archival design, not relaxing payment restrictions.

## Integration semantics and polling trade-offs

Polling uses current PostgreSQL transactional guarantees without a separate CDC/broker platform. CDC can reduce polling load/latency and enable fanout, but adds connector operations, schema compatibility, offsets and snapshot/cutover reconciliation. The migration condition is measured lag/throughput plus operational capability, not a daily volume headline.

At-least-once plus consumer deduplication is the implemented delivery contract. Exactly-once end-to-end would require proving every receiver's durable side effects and acknowledgement protocol; PostgreSQL uniqueness alone cannot prove it. Event snapshots trade payload size/privacy responsibilities for replay and reduced API coupling. Public order reads stay on the primary for authoritative read-after-write behavior; replicas are only for explicitly stale-tolerant access patterns.

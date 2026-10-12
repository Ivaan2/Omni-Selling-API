--liquibase formatted sql
--changeset orderms:001
CREATE TABLE products (
 id UUID PRIMARY KEY, name VARCHAR(200) NOT NULL, description VARCHAR(1000) NOT NULL,
 price NUMERIC(19,2) NOT NULL CHECK(price>=0), currency VARCHAR(3) NOT NULL CHECK(currency='EUR'),
 available BOOLEAN NOT NULL, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
 deleted_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE orders (
 id UUID PRIMARY KEY, customer_id VARCHAR(128) NOT NULL CHECK(length(trim(customer_id))>0),
 status VARCHAR(20) NOT NULL CHECK(status IN ('CREATED','PAID','CANCELLED')),
 total_amount NUMERIC(19,2) NOT NULL CHECK(total_amount>=0), currency VARCHAR(3) NOT NULL CHECK(currency='EUR'),
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, deleted_at TIMESTAMPTZ,
 version BIGINT NOT NULL DEFAULT 0, CHECK(deleted_at IS NULL OR status<>'PAID')
);
CREATE TABLE order_items (
 id UUID PRIMARY KEY, order_id UUID NOT NULL REFERENCES orders(id), product_id UUID NOT NULL REFERENCES products(id),
 product_name VARCHAR(200) NOT NULL, quantity INTEGER NOT NULL CHECK(quantity>0),
 unit_price NUMERIC(19,2) NOT NULL CHECK(unit_price>=0), currency VARCHAR(3) NOT NULL CHECK(currency='EUR'),
 UNIQUE(order_id,product_id)
);
CREATE TABLE payments (
 id UUID PRIMARY KEY, order_id UUID NOT NULL UNIQUE REFERENCES orders(id),
 status VARCHAR(20) NOT NULL CHECK(status IN ('PENDING','SUCCEEDED','FAILED','CANCELLED','UNKNOWN')),
 amount NUMERIC(19,2) NOT NULL CHECK(amount>=0), currency VARCHAR(3) NOT NULL CHECK(currency='EUR'),
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE payment_attempts (
 id UUID PRIMARY KEY, payment_id UUID NOT NULL REFERENCES payments(id), provider VARCHAR(50) NOT NULL,
 provider_reference VARCHAR(200), idempotency_reference VARCHAR(100) NOT NULL UNIQUE,
 status VARCHAR(20) NOT NULL CHECK(status IN ('PENDING','SUCCEEDED','FAILED','CANCELLED','UNKNOWN')),
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX payment_attempts_payment_idx ON payment_attempts(payment_id);
CREATE UNIQUE INDEX payment_attempts_single_active ON payment_attempts(payment_id) WHERE status IN ('PENDING','UNKNOWN');
CREATE INDEX payment_attempts_recovery_idx ON payment_attempts(updated_at) WHERE status IN ('PENDING','UNKNOWN');
CREATE TABLE fulfilments (
 id UUID PRIMARY KEY, order_id UUID NOT NULL UNIQUE REFERENCES orders(id),
 status VARCHAR(20) NOT NULL CHECK(status IN ('PENDING','PROCESSING','DELIVERED','FULFILLED')),
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE outbox_events (
 event_id UUID PRIMARY KEY, aggregate_id UUID NOT NULL, aggregate_type VARCHAR(50) NOT NULL,
 aggregate_version BIGINT NOT NULL CHECK(aggregate_version>=0), event_type VARCHAR(100) NOT NULL,
 event_version INTEGER NOT NULL CHECK(event_version>0), payload JSONB NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE outbox_deliveries (
 event_id UUID NOT NULL REFERENCES outbox_events(event_id), destination VARCHAR(50) NOT NULL,
 status VARCHAR(20) NOT NULL CHECK(status IN ('PENDING','PROCESSING','DELIVERED','EXHAUSTED')),
 retry_count INTEGER NOT NULL DEFAULT 0 CHECK(retry_count>=0), next_retry_at TIMESTAMPTZ NOT NULL,
 locked_until TIMESTAMPTZ, claim_token UUID, created_at TIMESTAMPTZ NOT NULL,
 delivered_at TIMESTAMPTZ, last_error VARCHAR(500), PRIMARY KEY(event_id,destination),
 CHECK(status<>'PROCESSING' OR (locked_until IS NOT NULL AND claim_token IS NOT NULL))
);
CREATE INDEX outbox_pending_idx ON outbox_deliveries(next_retry_at,created_at) WHERE status='PENDING';
CREATE INDEX outbox_lease_idx ON outbox_deliveries(locked_until) WHERE status='PROCESSING';
CREATE TABLE idempotency_records (
 id UUID PRIMARY KEY, customer_scope VARCHAR(128) NOT NULL, operation VARCHAR(150) NOT NULL,
 idempotency_key VARCHAR(200) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 state VARCHAR(20) NOT NULL CHECK(state IN ('PROCESSING','COMPLETED')),
 resource_id UUID, response JSONB, created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
 UNIQUE(customer_scope,operation,idempotency_key), CHECK(state<>'COMPLETED' OR response IS NOT NULL)
);
CREATE INDEX idempotency_expiry_idx ON idempotency_records(expires_at) WHERE state='COMPLETED';
-- Durable fake ledger is ONLY a simulation; it is not a substitute for a real provider.
CREATE TABLE simulated_provider_operations (
 idempotency_reference VARCHAR(100) PRIMARY KEY, amount NUMERIC(19,2) NOT NULL,
 currency VARCHAR(3) NOT NULL, outcome VARCHAR(20) NOT NULL CHECK(outcome IN ('SUCCEEDED','FAILED','UNKNOWN')),
 provider_reference VARCHAR(200) NOT NULL, created_at TIMESTAMPTZ NOT NULL
);

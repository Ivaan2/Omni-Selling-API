-- Explicit manual recovery of one exhausted event's external delivery only.
-- psql -v event_id=<uuid> -f scripts/recover-deliveries.sql
BEGIN;
UPDATE outbox_deliveries SET status='PENDING',retry_count=0,next_retry_at=CURRENT_TIMESTAMP,
 locked_until=NULL,claim_token=NULL,last_error=NULL
WHERE event_id=:'event_id'::uuid AND destination='HTTP' AND status='EXHAUSTED';
COMMIT;

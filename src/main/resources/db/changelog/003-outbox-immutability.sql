--liquibase formatted sql
--changeset orderms:003 splitStatements:false
CREATE FUNCTION reject_outbox_event_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Outbox event snapshots cannot be modified';
END;
$$;
CREATE TRIGGER outbox_events_immutable BEFORE UPDATE ON outbox_events
FOR EACH ROW EXECUTE FUNCTION reject_outbox_event_update();

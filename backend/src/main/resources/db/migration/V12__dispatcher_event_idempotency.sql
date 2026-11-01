-- Dispatcher event ingestion idempotency.
-- NULL external_ref remains valid for internally generated events.

ALTER TABLE events
    ADD COLUMN external_ref VARCHAR(255) NULL;

ALTER TABLE events
    ADD CONSTRAINT uk_events_type_external UNIQUE (event_type, external_ref);

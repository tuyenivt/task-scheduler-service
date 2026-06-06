-- V2__Cap_payload_and_metadata_size.sql
-- Defense-in-depth: cap JSONB payload and metadata sizes at the database level.
-- The primary check happens at the API boundary via @JsonSizeWithin; this CHECK
-- catches anything that bypasses the controller (direct DB writes, future
-- callers that skip validation).

ALTER TABLE scheduled_tasks
    ADD CONSTRAINT chk_payload_size
        CHECK (payload IS NULL OR octet_length(payload::text) <= 65536);

ALTER TABLE scheduled_tasks
    ADD CONSTRAINT chk_metadata_size
        CHECK (metadata IS NULL OR octet_length(metadata::text) <= 65536);

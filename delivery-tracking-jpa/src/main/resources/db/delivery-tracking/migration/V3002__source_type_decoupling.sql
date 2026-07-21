-- Decouple delivery tracking from notification store (platform#192)

-- delivery_attempt: add source columns, populate from notification_id, drop old column
ALTER TABLE delivery_attempt ADD COLUMN source_id VARCHAR(255);
ALTER TABLE delivery_attempt ADD COLUMN source_type VARCHAR(30);
UPDATE delivery_attempt SET source_id = notification_id, source_type = 'NOTIFICATION';
ALTER TABLE delivery_attempt ALTER COLUMN source_type SET NOT NULL;
ALTER TABLE delivery_attempt DROP COLUMN notification_id;
DROP INDEX IF EXISTS idx_delivery_attempt_notification;
CREATE INDEX idx_delivery_attempt_source ON delivery_attempt (source_id, source_type);

-- engagement_event: add source columns, populate, drop old column
ALTER TABLE engagement_event ADD COLUMN source_id VARCHAR(255);
ALTER TABLE engagement_event ADD COLUMN source_type VARCHAR(30);
UPDATE engagement_event SET source_id = notification_id, source_type = 'NOTIFICATION';
ALTER TABLE engagement_event ALTER COLUMN source_type SET NOT NULL;
ALTER TABLE engagement_event DROP COLUMN notification_id;
DROP INDEX IF EXISTS idx_engagement_event_notification;
CREATE INDEX idx_engagement_event_source ON engagement_event (source_id, source_type);

-- Make attempt_id nullable for independent retention lifecycle
ALTER TABLE engagement_event ALTER COLUMN attempt_id DROP NOT NULL;

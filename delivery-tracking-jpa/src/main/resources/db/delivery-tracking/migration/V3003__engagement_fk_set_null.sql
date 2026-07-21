-- Change engagement_event FK from CASCADE to SET NULL for independent retention (platform#192)
-- Separate migration: the inline FK from V3001 has a DB-specific auto-generated name.
-- PostgreSQL names it engagement_event_attempt_id_fkey; H2 uses CONSTRAINT_<N>.
-- This migration handles both by using DROP CONSTRAINT IF EXISTS for each convention.

ALTER TABLE engagement_event DROP CONSTRAINT IF EXISTS engagement_event_attempt_id_fkey;
ALTER TABLE engagement_event ADD CONSTRAINT engagement_event_attempt_id_fkey
    FOREIGN KEY (attempt_id) REFERENCES delivery_attempt(id) ON DELETE SET NULL;

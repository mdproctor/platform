ALTER TABLE platform_preference ADD COLUMN tenancy_id VARCHAR(100);

UPDATE platform_preference SET tenancy_id = '278776f9-e1b0-46fb-9032-8bddebdcf9ce';

ALTER TABLE platform_preference ALTER COLUMN tenancy_id SET NOT NULL;

ALTER TABLE platform_preference DROP CONSTRAINT uq_platform_preference;

ALTER TABLE platform_preference ADD CONSTRAINT uq_platform_preference
    UNIQUE (tenancy_id, scope, namespace, pref_name, sub_key);

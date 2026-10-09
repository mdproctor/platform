CREATE TABLE IF NOT EXISTS registry_entry (
    id              VARCHAR(255) NOT NULL,
    type            VARCHAR(100) NOT NULL,
    namespace       VARCHAR(255) NOT NULL,
    tenancy_id      VARCHAR(64)  NOT NULL,
    metadata        TEXT,
    registered_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_heartbeat  TIMESTAMP WITH TIME ZONE,
    ttl_seconds     BIGINT       NOT NULL,
    health          VARCHAR(20)  NOT NULL DEFAULT 'HEALTHY',
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_reg_type      ON registry_entry (type);
CREATE INDEX IF NOT EXISTS idx_reg_namespace  ON registry_entry (namespace);
CREATE INDEX IF NOT EXISTS idx_reg_tenancy    ON registry_entry (tenancy_id);
CREATE INDEX IF NOT EXISTS idx_reg_health     ON registry_entry (health);

CREATE SEQUENCE IF NOT EXISTS registry_relationship_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS registry_relationship (
    id        BIGINT       NOT NULL DEFAULT nextval('registry_relationship_seq'),
    source_id VARCHAR(255) NOT NULL,
    target_id VARCHAR(255) NOT NULL,
    type      VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_reg_rel UNIQUE (source_id, target_id)
);

CREATE INDEX IF NOT EXISTS idx_rel_source ON registry_relationship (source_id);
CREATE INDEX IF NOT EXISTS idx_rel_target ON registry_relationship (target_id);

CREATE TABLE IF NOT EXISTS registry_cascade_rule (
    relationship_type     VARCHAR(100) NOT NULL,
    on_source_deregister  VARCHAR(30)  NOT NULL,
    PRIMARY KEY (relationship_type)
);

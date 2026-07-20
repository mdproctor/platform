CREATE TABLE subject_view (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    tenancy_id      VARCHAR(255) NOT NULL,
    label_pattern   VARCHAR(500) NOT NULL,
    scope           VARCHAR(500),
    sort_field      VARCHAR(50),
    sort_direction  VARCHAR(4),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_subject_view_tenancy ON subject_view (tenancy_id);

CREATE TABLE view_membership (
    subject_id  UUID NOT NULL,
    view_id     UUID NOT NULL,
    view_name   VARCHAR(255) NOT NULL,
    PRIMARY KEY (subject_id, view_id)
);

CREATE INDEX idx_view_membership_subject ON view_membership (subject_id);
CREATE INDEX idx_view_membership_view ON view_membership (view_id);

-- V10 — Phase 2 (P2-6): review decisions, per-item scoring, and granular review/score grants.
--
-- can_review / can_score are granular booleans on app_user (FOUNDER always has both). A `review` records
-- a reviewer's accept/reject of a submission (multiple rows allowed — a FOUNDER may override a lead's
-- decision; the latest wins). `task_score` holds a per-task score for MANUAL (FULL/PARTIAL/ZERO ->
-- points x {1,0.5,0}) and NUMERIC (value scored via `numeric_band`) items. Bands are [lower, upper):
-- lower inclusive, upper exclusive; NULL lower = -inf, NULL upper = +inf.

ALTER TABLE app_user ADD COLUMN can_score  BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE app_user ADD COLUMN can_review BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE review (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID        NOT NULL REFERENCES organization(id),
    submission_id    UUID        NOT NULL REFERENCES submission(id),
    reviewer_user_id UUID        NOT NULL REFERENCES app_user(id),
    decision         VARCHAR(8)  NOT NULL,
    reason           VARCHAR(64),                          -- quick reject reason code (optional)
    comment          TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT review_decision_chk CHECK (decision IN ('ACCEPTED', 'REJECTED'))
);
CREATE INDEX idx_review_submission ON review (submission_id, created_at);

CREATE TABLE task_score (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID          NOT NULL REFERENCES organization(id),
    task_instance_id UUID          NOT NULL REFERENCES task_instance(id),
    item_id          UUID          NOT NULL REFERENCES checklist_item(id),
    reviewer_user_id UUID          NOT NULL REFERENCES app_user(id),
    grade            VARCHAR(8),                            -- MANUAL: FULL/PARTIAL/ZERO; null for NUMERIC
    numeric_value    NUMERIC(18,4),                         -- NUMERIC: the measured value; null for MANUAL
    awarded_points   INT           NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT task_score_grade_chk CHECK (grade IS NULL OR grade IN ('FULL', 'PARTIAL', 'ZERO')),
    CONSTRAINT task_score_unique UNIQUE (task_instance_id)   -- one score per task; re-scoring updates it
);
CREATE INDEX idx_task_score_org ON task_score (organization_id);

CREATE TABLE numeric_band (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID          NOT NULL REFERENCES organization(id),
    item_id         UUID          NOT NULL REFERENCES checklist_item(id),
    lower_bound     NUMERIC(18,4),                          -- inclusive; NULL = -infinity
    upper_bound     NUMERIC(18,4),                          -- exclusive; NULL = +infinity
    points          INT           NOT NULL,
    label           VARCHAR(64),
    sort_order      INT           NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_numeric_band_item ON numeric_band (item_id, sort_order, id);

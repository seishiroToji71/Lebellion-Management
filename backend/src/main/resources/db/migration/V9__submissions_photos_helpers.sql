-- V9 — Phase 2 (P2-5): submissions, photos, helper tags, and blocked duplicate attempts.
--
-- A submission is one answer (+ optional photo) against a task_instance, stamped with the SERVER receipt
-- time (client time is never trusted) and a `late` flag. Photos carry SHA-256 + a 64-bit dHash and are
-- denormalised with (unit_id, item_id) so duplicate search scopes by (item, unit) over a 60-day window —
-- not by a single task_instance. Helper tags credit co-workers; confirmed_at is set only by the tagged
-- employee. Every blocked duplicate attempt is recorded (a signal for the Founder).

CREATE TABLE submission (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id      UUID        NOT NULL REFERENCES organization(id),
    task_instance_id     UUID        NOT NULL REFERENCES task_instance(id),
    submitted_by_user_id UUID        NOT NULL REFERENCES app_user(id),
    answer               BOOLEAN     NOT NULL,
    received_at          TIMESTAMPTZ NOT NULL,            -- server receipt time (authoritative)
    late                 BOOLEAN     NOT NULL DEFAULT false,
    status               VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED',
    auto_flags           JSONB,                           -- nullable; set for a flagged near-duplicate (static scene)
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT submission_status_chk CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REJECTED'))
);
CREATE INDEX idx_submission_task ON submission (task_instance_id);
CREATE INDEX idx_submission_org  ON submission (organization_id, created_at);

CREATE TABLE photo (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization(id),
    submission_id   UUID        NOT NULL REFERENCES submission(id),
    unit_id         UUID        NOT NULL REFERENCES unit(id),
    item_id         UUID        NOT NULL REFERENCES checklist_item(id),
    storage_key     VARCHAR(128) NOT NULL UNIQUE,         -- opaque, unpredictable; never guessable
    content_type    VARCHAR(64) NOT NULL,
    size_bytes      BIGINT      NOT NULL,
    sha256          VARCHAR(64) NOT NULL,
    dhash           BIGINT      NOT NULL,                 -- 64-bit perceptual hash (signed)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),   -- server receipt time
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT photo_size_chk CHECK (size_bytes > 0)
);
-- duplicate search: scope by (org, unit, item) within the 60-day window; sha256 for the exact probe
CREATE INDEX idx_photo_dupscope ON photo (organization_id, unit_id, item_id, created_at);
CREATE INDEX idx_photo_sha      ON photo (organization_id, unit_id, item_id, sha256);
CREATE INDEX idx_photo_submission ON photo (submission_id);

CREATE TABLE submission_helper (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization(id),
    submission_id   UUID        NOT NULL REFERENCES submission(id),
    employee_id     UUID        NOT NULL REFERENCES app_user(id),
    confirmed_at    TIMESTAMPTZ,                          -- set only by the tagged employee themselves
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT submission_helper_unique UNIQUE (submission_id, employee_id)
);
CREATE INDEX idx_submission_helper_employee ON submission_helper (employee_id);

CREATE TABLE blocked_attempt (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID        NOT NULL REFERENCES organization(id),
    task_instance_id UUID        NOT NULL REFERENCES task_instance(id),
    item_id          UUID        NOT NULL REFERENCES checklist_item(id),
    unit_id          UUID        NOT NULL REFERENCES unit(id),
    user_id          UUID        NOT NULL REFERENCES app_user(id),
    sha256           VARCHAR(64) NOT NULL,
    dhash            BIGINT      NOT NULL,
    reason           VARCHAR(24) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT blocked_attempt_reason_chk CHECK (reason IN ('EXACT_SHA', 'NEAR_DUPLICATE'))
);
CREATE INDEX idx_blocked_attempt_scope ON blocked_attempt (organization_id, unit_id, item_id, created_at);

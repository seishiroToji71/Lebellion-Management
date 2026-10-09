-- V11 — Phase 2 (P2-6b): KPI score sheets, bands, and monthly per-employee evaluations.
--
-- kpi_criterion is a STANDALONE catalog (no tasks are created from it; it is not criterion_library and
-- not checklist_item). score_sheet (per role) references criteria with per-sheet points summing to 100.
-- score_band is the bonus/base/penalty scale (inclusive integer ranges covering 0..100). evaluation is
-- the subject: one employee × score_sheet × month; the reviewer sets awarded_points per item (no v1
-- auto-hints — there is no criterion<->task link). FINALIZE snapshots and freezes the result.

CREATE TABLE kpi_criterion (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID        NOT NULL REFERENCES organization(id),
    code              VARCHAR(16) NOT NULL,                  -- C001… (stable key; seed is idempotent on it)
    type              VARCHAR(16) NOT NULL,                  -- PHOTO / MANUAL / NUMERIC (catalog suggestion)
    title_ru          VARCHAR(255) NOT NULL,
    title_uz          VARCHAR(255) NOT NULL,
    original_text     TEXT,
    checklist_item_id UUID        REFERENCES checklist_item(id),  -- reserved; NOT used in v1
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT kpi_criterion_type_chk CHECK (type IN ('PHOTO', 'MANUAL', 'NUMERIC')),
    CONSTRAINT kpi_criterion_code_unique UNIQUE (organization_id, code)
);

CREATE TABLE score_sheet (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id      UUID         NOT NULL REFERENCES organization(id),
    role_key             VARCHAR(64)  NOT NULL,              -- BOSH_OSHPAZ… (stable; seed idempotent on it)
    name_ru              VARCHAR(255) NOT NULL,
    name_uz              VARCHAR(255) NOT NULL,
    position_suggestion  VARCHAR(128),
    active               BOOLEAN      NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT score_sheet_role_unique UNIQUE (organization_id, role_key)
);

CREATE TABLE score_sheet_item (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organization(id),
    score_sheet_id   UUID         NOT NULL REFERENCES score_sheet(id),
    kpi_criterion_id UUID         NOT NULL REFERENCES kpi_criterion(id),
    points           INT          NOT NULL,
    direction        VARCHAR(255),
    sort_order       INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT score_sheet_item_points_chk CHECK (points >= 0)
);
CREATE INDEX idx_score_sheet_item_sheet ON score_sheet_item (score_sheet_id, sort_order, id);

CREATE TABLE score_band (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization(id),
    score_sheet_id  UUID        NOT NULL REFERENCES score_sheet(id),
    from_score      INT         NOT NULL,                   -- inclusive
    to_score        INT         NOT NULL,                   -- inclusive
    label           VARCHAR(16) NOT NULL,
    percent         INT,                                    -- null = unknown (hall/supply)
    sort_order      INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT score_band_label_chk CHECK (label IN ('BONUS', 'BASE', 'PENALTY')),
    CONSTRAINT score_band_range_chk CHECK (from_score <= to_score AND from_score >= 0 AND to_score <= 100)
);
CREATE INDEX idx_score_band_sheet ON score_band (score_sheet_id, from_score);

CREATE TABLE evaluation (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organization(id),
    employee_id      UUID         NOT NULL REFERENCES app_user(id),
    score_sheet_id   UUID         NOT NULL REFERENCES score_sheet(id),
    period_month     DATE         NOT NULL,                 -- 1st of the month (Asia/Tashkent)
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    reviewer_user_id UUID         NOT NULL REFERENCES app_user(id),
    final_score      NUMERIC(6,1),                          -- set at FINALIZE (may be x.5)
    band_label       VARCHAR(16),
    band_percent     INT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT evaluation_status_chk CHECK (status IN ('DRAFT', 'FINALIZED')),
    CONSTRAINT evaluation_unique UNIQUE (organization_id, employee_id, score_sheet_id, period_month)
);

CREATE TABLE evaluation_item (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID         NOT NULL REFERENCES organization(id),
    evaluation_id       UUID         NOT NULL REFERENCES evaluation(id),
    score_sheet_item_id UUID         NOT NULL REFERENCES score_sheet_item(id),
    awarded_points      NUMERIC(6,1) NOT NULL,              -- reviewer's value ∈ {0, points/2, points}
    max_points          INT,                                -- snapshot at FINALIZE
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT evaluation_item_unique UNIQUE (evaluation_id, score_sheet_item_id)
);

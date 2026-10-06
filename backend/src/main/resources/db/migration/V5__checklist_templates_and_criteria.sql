-- V5 — Phase 2 (P2-1): a reusable, org-scoped criterion library plus checklist templates & items.
--
-- A unit may have MANY templates (e.g. one per position: «Повар мангалщик»). A template's items either
-- LINK to a library criterion (criterion_id set, inline title/standard columns NULL — text is stored once,
-- never duplicated) or are AD-HOC (criterion_id NULL, inline title columns carry the text). Scalar config
-- (points/critical/static_scene/dhash_threshold/photo_required) is materialised on the item so it can be
-- overridden per item; a linked item seeds these from the criterion's defaults at creation time.
--
-- Deferred on purpose: numeric_scale (JSONB) and its scoring bands land in P2-6; position_id is reserved
-- here as a nullable column (no FK yet) until the "positions" model is decided (P2-2).

CREATE TABLE criterion_library (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id          UUID         NOT NULL REFERENCES organization(id),
    type                     VARCHAR(16)  NOT NULL,
    title_ru                 VARCHAR(120) NOT NULL,
    title_uz                 VARCHAR(120) NOT NULL,
    standard_ru              VARCHAR(500),
    standard_uz              VARCHAR(500),
    original_text            TEXT,
    default_photo_required   BOOLEAN      NOT NULL DEFAULT false,
    default_points           INT          NOT NULL DEFAULT 0,
    default_critical         BOOLEAN      NOT NULL DEFAULT false,
    default_static_scene     BOOLEAN      NOT NULL DEFAULT false,
    default_dhash_threshold  INT          NOT NULL DEFAULT 6,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT criterion_type_chk  CHECK (type IN ('PHOTO', 'MANUAL', 'NUMERIC')),
    CONSTRAINT criterion_points_chk CHECK (default_points >= 0),
    CONSTRAINT criterion_dhash_chk  CHECK (default_dhash_threshold BETWEEN 0 AND 64)
);
CREATE INDEX idx_criterion_org_created ON criterion_library (organization_id, created_at, id);

CREATE TABLE checklist_template (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organization(id),
    unit_id          UUID         NOT NULL REFERENCES unit(id),
    position_id      UUID,        -- reserved for the future positions model (P2-2); nullable, no FK yet
    name             VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_template_org_created ON checklist_template (organization_id, created_at, id);
CREATE INDEX idx_template_unit        ON checklist_template (unit_id);

CREATE TABLE checklist_item (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID         NOT NULL REFERENCES organization(id),
    template_id      UUID         NOT NULL REFERENCES checklist_template(id),
    criterion_id     UUID         REFERENCES criterion_library(id),
    type             VARCHAR(16)  NOT NULL,
    title_ru         VARCHAR(120),
    title_uz         VARCHAR(120),
    standard_ru      VARCHAR(500),
    standard_uz      VARCHAR(500),
    original_text    TEXT,
    photo_required   BOOLEAN      NOT NULL DEFAULT false,
    points           INT          NOT NULL DEFAULT 0,
    critical         BOOLEAN      NOT NULL DEFAULT false,
    static_scene     BOOLEAN      NOT NULL DEFAULT false,
    dhash_threshold  INT          NOT NULL DEFAULT 6,
    sort_order       INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT item_type_chk   CHECK (type IN ('PHOTO', 'MANUAL', 'NUMERIC')),
    CONSTRAINT item_points_chk CHECK (points >= 0),
    CONSTRAINT item_dhash_chk  CHECK (dhash_threshold BETWEEN 0 AND 64),
    -- a linked item must not duplicate library text; an ad-hoc item must carry its own titles
    CONSTRAINT item_text_source_chk CHECK (
        (criterion_id IS NOT NULL AND title_ru IS NULL AND title_uz IS NULL)
        OR (criterion_id IS NULL AND title_ru IS NOT NULL AND title_uz IS NOT NULL)
    )
);
CREATE INDEX idx_item_org      ON checklist_item (organization_id);
CREATE INDEX idx_item_template ON checklist_item (template_id, sort_order, id);

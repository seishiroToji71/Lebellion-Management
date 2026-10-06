-- V6 — Phase 2 (P2-3): schedules that drive a checklist template, plus generated task instances.
--
-- A schedule drives ONE template. DAILY schedules fire at explicit time-of-day slots (Asia/Tashkent),
-- every `interval_days` from `anchor_date` (1 = daily, 2 = every other day). A slot may override the
-- item's photo_required. WEEKLY schedules fire once per ISO calendar week, deadline = end of Sunday; a
-- WEEKLY template's items ARE its zones (one photo per zone, progress N of M), so no slot rows.
--
-- The generator materialises one task_instance per (schedule, item, occurrence). Occurrence identity is
-- `period_key` ("D:<date>:<HH:mm>" for a daily slot, "W:<isoYear>-W<week>" for a week); the unique
-- constraint makes generation idempotent. due_at/scheduled_at are stored in UTC. Only PENDING is produced
-- here — the rest of the lifecycle (SUBMITTED/MISSED/...) arrives in P2-4, so the CHECK already lists them.

CREATE TABLE schedule (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id      UUID        NOT NULL REFERENCES organization(id),
    template_id          UUID        NOT NULL REFERENCES checklist_template(id),
    recurrence           VARCHAR(16) NOT NULL,
    interval_days        INT         NOT NULL DEFAULT 1,
    anchor_date          DATE,
    slot_window_minutes  INT         NOT NULL DEFAULT 60,
    active               BOOLEAN     NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT schedule_recurrence_chk CHECK (recurrence IN ('DAILY', 'WEEKLY')),
    CONSTRAINT schedule_interval_chk   CHECK (interval_days >= 1),
    CONSTRAINT schedule_window_chk     CHECK (slot_window_minutes > 0),
    -- DAILY needs an anchor to count intervals from; WEEKLY ignores interval_days (fixed to 1)
    CONSTRAINT schedule_daily_anchor_chk CHECK (
        (recurrence = 'DAILY' AND anchor_date IS NOT NULL)
        OR (recurrence = 'WEEKLY' AND interval_days = 1)
    )
);
CREATE INDEX idx_schedule_org      ON schedule (organization_id);
CREATE INDEX idx_schedule_template ON schedule (template_id);
CREATE INDEX idx_schedule_active   ON schedule (active);

CREATE TABLE schedule_slot (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization(id),
    schedule_id     UUID        NOT NULL REFERENCES schedule(id),
    slot_time       TIME        NOT NULL,
    photo_required  BOOLEAN,    -- nullable override of the item's photo_required; null => use the item's
    sort_order      INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT schedule_slot_unique UNIQUE (schedule_id, slot_time)
);
CREATE INDEX idx_schedule_slot_schedule ON schedule_slot (schedule_id, sort_order, id);

CREATE TABLE task_instance (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization(id),
    schedule_id     UUID        NOT NULL REFERENCES schedule(id),
    template_id     UUID        NOT NULL REFERENCES checklist_template(id),
    item_id         UUID        NOT NULL REFERENCES checklist_item(id),
    unit_id         UUID        NOT NULL REFERENCES unit(id),
    slot_time       TIME,                   -- the DAILY slot this instance belongs to; null for WEEKLY
    period_key      VARCHAR(40) NOT NULL,
    scheduled_at    TIMESTAMPTZ,            -- nominal occurrence instant (slot start, UTC); null for WEEKLY
    due_at          TIMESTAMPTZ NOT NULL,   -- deadline (UTC): slot start + window, or end of Sunday
    photo_required  BOOLEAN     NOT NULL,   -- effective: slot override ?? item.photo_required
    status          VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT task_instance_status_chk CHECK (
        status IN ('PENDING', 'SUBMITTED', 'REJECTED', 'EXTENSION_REQUESTED', 'DONE', 'MISSED', 'EXTENDED')
    ),
    CONSTRAINT task_instance_unique UNIQUE (schedule_id, item_id, period_key)
);
CREATE INDEX idx_task_instance_org        ON task_instance (organization_id);
CREATE INDEX idx_task_instance_unit_due   ON task_instance (unit_id, due_at);
CREATE INDEX idx_task_instance_status_due ON task_instance (status, due_at);

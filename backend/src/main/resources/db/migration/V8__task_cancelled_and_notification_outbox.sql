-- V8 — Phase 2 (P2-4): CANCELLED status, partial uniqueness, and the notification outbox.
--
-- On schedule edit/deactivate, future PENDING instances are CANCELLED (not deleted): an offline client
-- may still submit to one, and we want a clear error + a preserved attempt, not a 404. Uniqueness must
-- therefore ignore CANCELLED rows, so regenerating the same occurrence inserts a fresh PENDING alongside
-- the cancelled one.

ALTER TABLE task_instance DROP CONSTRAINT task_instance_status_chk;
ALTER TABLE task_instance ADD CONSTRAINT task_instance_status_chk CHECK (
    status IN ('PENDING', 'SUBMITTED', 'REJECTED', 'EXTENSION_REQUESTED', 'DONE', 'MISSED', 'EXTENDED', 'CANCELLED')
);

ALTER TABLE task_instance DROP CONSTRAINT task_instance_unique;
CREATE UNIQUE INDEX task_instance_unique ON task_instance (schedule_id, item_id, period_key)
    WHERE status <> 'CANCELLED';

CREATE TABLE notification_outbox (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization(id),
    type            VARCHAR(48) NOT NULL,          -- TASK_MISSED / TASK_SUBMITTED / ...
    payload         JSONB,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts        INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,                   -- no dispatcher yet (P2-4 writes rows only)
    CONSTRAINT notification_outbox_status_chk CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);
CREATE INDEX idx_notification_outbox_status ON notification_outbox (status, created_at);

-- V4 — append-only audit_log + branch-manager login contact carried by the invite.

CREATE TABLE audit_log (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization(id),
    actor_user_id   UUID        REFERENCES app_user(id),   -- who performed it (nullable for system events)
    event_type      VARCHAR(64) NOT NULL,                  -- EMPLOYEE_JOINED / MANAGER_JOINED / RECOVERY_JOIN / PASSWORD_RESET_BY_FOUNDER / ...
    target_type     VARCHAR(32),                           -- e.g. USER, INVITE
    target_id       UUID,
    metadata        JSONB,                                 -- arbitrary event details
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_org_created ON audit_log (organization_id, created_at DESC);
CREATE INDEX idx_audit_log_target      ON audit_log (target_type, target_id);
CREATE INDEX idx_audit_log_event       ON audit_log (event_type);

-- BRANCH_MANAGER login identity is set by the FOUNDER on the invite (the code determines everything);
-- join copies it onto the new manager (who logs in with email/phone + password).
ALTER TABLE invite ADD COLUMN email VARCHAR(320);
ALTER TABLE invite ADD COLUMN phone VARCHAR(20);

-- V7 — Phase 2 (P2-2): Unit-level lead / acting_lead.
--
-- A unit has a lead (e.g. the kitchen's chef) and an optional acting_lead (temporary stand-in). The
-- effective lead is resolved acting_lead -> lead -> the branch's BRANCH_MANAGER (fallback). Both columns
-- are nullable and reference app_user. Role→position binding under a unit is still deferred (see
-- checklist_template.position_id), so leads are assigned directly here.

ALTER TABLE unit ADD COLUMN lead_employee_id        UUID REFERENCES app_user(id);
ALTER TABLE unit ADD COLUMN acting_lead_employee_id UUID REFERENCES app_user(id);

CREATE INDEX idx_unit_lead        ON unit (lead_employee_id);
CREATE INDEX idx_unit_acting_lead ON unit (acting_lead_employee_id);

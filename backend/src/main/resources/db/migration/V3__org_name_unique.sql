-- V3 — уникальность имени организации (регистронезависимо), чтобы register честно отдавал 409.
CREATE UNIQUE INDEX uq_organization_name_lower ON organization (lower(name));

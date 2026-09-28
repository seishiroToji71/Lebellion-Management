CREATE TABLE organization (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE branch (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization(id),
    name            VARCHAR(255) NOT NULL,
    address         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE unit (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization(id),
    branch_id       UUID        NOT NULL REFERENCES branch(id),
    name            VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE employee (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization(id),
    unit_id         UUID        NOT NULL REFERENCES unit(id),
    name            VARCHAR(255) NOT NULL,
    phone           VARCHAR(20),
    role            VARCHAR(50)  NOT NULL DEFAULT 'EMPLOYEE',
    invite_code     VARCHAR(50)  UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_branch_org    ON branch(organization_id);
CREATE INDEX idx_unit_org      ON unit(organization_id);
CREATE INDEX idx_unit_branch   ON unit(branch_id);
CREATE INDEX idx_employee_org  ON employee(organization_id);
CREATE INDEX idx_employee_unit ON employee(unit_id);

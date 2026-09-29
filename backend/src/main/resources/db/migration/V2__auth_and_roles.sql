-- V2 — authentication & roles
-- Единая таблица пользователей (FOUNDER / BRANCH_MANAGER / EMPLOYEE),
-- инвайты (HMAC-хеш кода) и refresh-токены (opaque-хеш, семьи, ротация, device-binding).
-- Все таблицы несут organization_id. EXPIRED у инвайта — вычисляемый статус, в БД не хранится.

------------------------------------------------------------------------
-- 1. employee -> app_user
------------------------------------------------------------------------
ALTER TABLE employee RENAME TO app_user;

-- переименовать унаследованные PK/FK/индексы под новое имя таблицы
ALTER TABLE app_user RENAME CONSTRAINT employee_pkey                 TO app_user_pkey;
ALTER TABLE app_user RENAME CONSTRAINT employee_organization_id_fkey TO app_user_organization_id_fkey;
ALTER TABLE app_user RENAME CONSTRAINT employee_unit_id_fkey         TO app_user_unit_id_fkey;
ALTER INDEX idx_employee_org  RENAME TO idx_app_user_org;
ALTER INDEX idx_employee_unit RENAME TO idx_app_user_unit;

-- коды инвайтов уезжают в таблицу invite (HMAC-хеш); снимаем legacy-колонку
-- (её UNIQUE-constraint employee_invite_code_key удаляется вместе с колонкой)
ALTER TABLE app_user DROP COLUMN invite_code;

-- unit теперь только у EMPLOYEE; у менеджера/фаундера unit нет
ALTER TABLE app_user ALTER COLUMN unit_id DROP NOT NULL;

ALTER TABLE app_user
    ADD COLUMN branch_id            UUID        REFERENCES branch(id),
    ADD COLUMN email                VARCHAR(320),
    ADD COLUMN password_hash        VARCHAR(255),
    ADD COLUMN must_change_password BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN token_version        INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN is_active            BOOLEAN     NOT NULL DEFAULT true;

-- допустимые роли
ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_role
    CHECK (role IN ('FOUNDER', 'BRANCH_MANAGER', 'EMPLOYEE'));

-- роль-специфичная форма строки:
--   FOUNDER        — без branch/unit, пароль обязателен, есть email или phone
--   BRANCH_MANAGER — привязан к branch, без unit, пароль обязателен, есть email или phone
--   EMPLOYEE       — привязан к unit, без branch, без пароля, must_change_password=false
ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_role_shape CHECK (
        CASE role
            WHEN 'FOUNDER' THEN
                branch_id IS NULL
                AND unit_id IS NULL
                AND password_hash IS NOT NULL
                AND (email IS NOT NULL OR phone IS NOT NULL)
            WHEN 'BRANCH_MANAGER' THEN
                branch_id IS NOT NULL
                AND unit_id IS NULL
                AND password_hash IS NOT NULL
                AND (email IS NOT NULL OR phone IS NOT NULL)
            WHEN 'EMPLOYEE' THEN
                unit_id IS NOT NULL
                AND branch_id IS NULL
                AND password_hash IS NULL
                AND must_change_password = false
            ELSE false
        END
    );

-- email как логин: глобально уникален (регистронезависимо)
CREATE UNIQUE INDEX uq_app_user_email_lower
    ON app_user (lower(email))
    WHERE email IS NOT NULL;

-- phone как логин/идентичность: глобально уникален
CREATE UNIQUE INDEX uq_app_user_phone
    ON app_user (phone)
    WHERE phone IS NOT NULL;

-- один активный FOUNDER на организацию
CREATE UNIQUE INDEX uq_app_user_one_founder_per_org
    ON app_user (organization_id)
    WHERE role = 'FOUNDER' AND is_active;

-- один активный BRANCH_MANAGER на филиал
CREATE UNIQUE INDEX uq_app_user_one_manager_per_branch
    ON app_user (branch_id)
    WHERE role = 'BRANCH_MANAGER' AND is_active;

CREATE INDEX idx_app_user_branch ON app_user (branch_id);

------------------------------------------------------------------------
-- 2. invite
------------------------------------------------------------------------
CREATE TABLE invite (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID         NOT NULL REFERENCES organization(id),
    code_hmac          CHAR(64)     NOT NULL,                 -- HMAC-SHA256(код) в hex
    role               VARCHAR(50)  NOT NULL,                 -- роль, которую выдаёт инвайт
    branch_id          UUID         REFERENCES branch(id),
    unit_id            UUID         REFERENCES unit(id),
    target_employee_id UUID         REFERENCES app_user(id),  -- recovery-инвайт: привязка к существующему пользователю
    status             VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    expires_at         TIMESTAMPTZ  NOT NULL,
    created_by         UUID         NOT NULL REFERENCES app_user(id),
    used_by            UUID         REFERENCES app_user(id),
    used_at            TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- через инвайт выдаются только EMPLOYEE и BRANCH_MANAGER; FOUNDER — только register
    CONSTRAINT chk_invite_role   CHECK (role IN ('EMPLOYEE', 'BRANCH_MANAGER')),
    -- в БД живут только PENDING/USED/REVOKED; EXPIRED вычисляется по expires_at
    CONSTRAINT chk_invite_status CHECK (status IN ('PENDING', 'USED', 'REVOKED')),

    -- форма инвайта:
    --   recovery (target задан) — идентичность берём у целевого пользователя, branch/unit не нужны
    --   EMPLOYEE               — задан unit, без branch
    --   BRANCH_MANAGER         — задан branch, без unit
    CONSTRAINT chk_invite_shape CHECK (
        CASE
            WHEN target_employee_id IS NOT NULL THEN true
            WHEN role = 'EMPLOYEE'              THEN unit_id IS NOT NULL AND branch_id IS NULL
            WHEN role = 'BRANCH_MANAGER'        THEN branch_id IS NOT NULL AND unit_id IS NULL
            ELSE false
        END
    ),

    -- согласованность полей потребления
    CONSTRAINT chk_invite_used CHECK (
        (status = 'USED'  AND used_by IS NOT NULL AND used_at IS NOT NULL) OR
        (status <> 'USED' AND used_by IS NULL     AND used_at IS NULL)
    )
);

-- код инвайта уникален глобально (это секрет-токен)
CREATE UNIQUE INDEX uq_invite_code_hmac ON invite (code_hmac);

-- не более одного живого recovery-инвайта на пользователя
CREATE UNIQUE INDEX uq_invite_pending_recovery
    ON invite (target_employee_id)
    WHERE status = 'PENDING' AND target_employee_id IS NOT NULL;

CREATE INDEX idx_invite_org     ON invite (organization_id);
CREATE INDEX idx_invite_status  ON invite (status);
CREATE INDEX idx_invite_expires ON invite (expires_at);

------------------------------------------------------------------------
-- 3. refresh_token
------------------------------------------------------------------------
CREATE TABLE refresh_token (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID         NOT NULL REFERENCES organization(id),
    user_id             UUID         NOT NULL REFERENCES app_user(id),
    family_id           UUID         NOT NULL,                 -- цепочка ротаций одной сессии
    parent_id           UUID         REFERENCES refresh_token(id), -- предыдущий токен в цепочке
    token_hash          CHAR(64)     NOT NULL,                 -- SHA-256(opaque refresh) в hex
    device_id           VARCHAR(255) NOT NULL,                 -- сессия привязана к устройству
    issued_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ  NOT NULL,                 -- скользящий срок (по неактивности)
    absolute_expires_at TIMESTAMPTZ,                           -- абсолютный потолок (менеджер/фаундер = 90д; EMPLOYEE = NULL)
    rotated_at          TIMESTAMPTZ,                           -- когда токен ротирован (в grace ещё валиден)
    revoked_at          TIMESTAMPTZ,
    revoked_reason      VARCHAR(40),                            -- LOGOUT / THEFT_DETECTED / FAMILY_REVOKED / DEVICE_MISMATCH / PASSWORD_RESET / RECOVERY ...
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_refresh_expiry  CHECK (expires_at > issued_at),
    CONSTRAINT chk_refresh_revoked CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);

-- хеш токена уникален глобально
CREATE UNIQUE INDEX uq_refresh_token_hash ON refresh_token (token_hash);

-- не более одного «живого» (не ротированного, не отозванного) токена в семье
CREATE UNIQUE INDEX uq_refresh_active_per_family
    ON refresh_token (family_id)
    WHERE rotated_at IS NULL AND revoked_at IS NULL;

CREATE INDEX idx_refresh_user    ON refresh_token (user_id);
CREATE INDEX idx_refresh_family  ON refresh_token (family_id);
CREATE INDEX idx_refresh_expires ON refresh_token (expires_at);

------------------------------------------------------------------------
-- 4. password_reset — self-service сброс пароля (FOUNDER / BRANCH_MANAGER)
--    request -> код с TTL ~15 мин (HMAC-хеш в БД) -> confirm с новым паролем.
--    После confirm приложение отзывает все refresh-сессии (логика в блоке (в)).
--    EMPLOYEE сюда не попадает (у него нет пароля) — это гарантирует сервисный слой.
------------------------------------------------------------------------
CREATE TABLE password_reset (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL REFERENCES organization(id),
    user_id         UUID         NOT NULL REFERENCES app_user(id),
    code_hmac       CHAR(64)     NOT NULL,              -- HMAC-SHA256(код подтверждения) в hex
    channel         VARCHAR(10)  NOT NULL,              -- куда отправлен код
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER      NOT NULL DEFAULT 0,    -- неверные попытки confirm (для лок-аута)
    expires_at      TIMESTAMPTZ  NOT NULL,             -- TTL ~15 минут
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_password_reset_channel  CHECK (channel IN ('EMAIL', 'SMS')),
    CONSTRAINT chk_password_reset_status   CHECK (status IN ('PENDING', 'USED', 'REVOKED')),
    CONSTRAINT chk_password_reset_used     CHECK ((status = 'USED') = (used_at IS NOT NULL)),
    CONSTRAINT chk_password_reset_attempts CHECK (attempts >= 0)
);

-- не более одного живого кода сброса на пользователя (новый запрос отзывает прежний)
CREATE UNIQUE INDEX uq_password_reset_pending
    ON password_reset (user_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_password_reset_org     ON password_reset (organization_id);
CREATE INDEX idx_password_reset_user    ON password_reset (user_id);
CREATE INDEX idx_password_reset_expires ON password_reset (expires_at);

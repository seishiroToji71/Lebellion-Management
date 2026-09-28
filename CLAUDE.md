# Lebellion Management — project context for Claude Code

Read this file fully before doing anything. Ask before deviating from the decisions below.

## What we are building
A mobile-first (Android + iOS) checklist & KPI inspection system for restaurant chains.
First client: a supervising inspector ("Founder" role) who oversees ~10 restaurant branches
and can visit each in person only about once a month. Later: sold to other companies by subscription
(so multi-tenancy is mandatory from day 1).

Core loop:
1. SCHEDULE — what must be done and when (cleaning slots, opening/closing checklists, weekly deep-clean zones).
2. PROOF — an employee answers Yes/No and attaches a photo taken with the in-app camera.
3. REVIEW — the Founder accepts/rejects photos from a phone, sees per-branch dashboards.

Reference product: inspekt.uz (Telegram Mini App). Its known weaknesses that we must fix:
- it accepts any photo (e.g. a photo of a wall for "clean the fridge");
- the same old photo can be re-sent on different days;
- no deadlines calendar, no overview of what was missed.

## Product decisions (already made — do not relitigate)
- Mobile app is the main product (Android first, iOS second wave). No desktop web UI in v1.
- Minimalist, fast UI. Employee submits a task in <= 3 taps. No decorative screens.
- Visible task statuses: `DONE`, `MISSED`, `EXTENDED` (deferred). Internally there is also `PENDING`,
  `SUBMITTED` (awaiting review), `REJECTED`, `EXTENSION_REQUESTED`.
- NO AI photo-content checker in v1 (the Founder reviews photos himself). But keep a nullable
  `auto_flags` (jsonb) column on submissions so a checker can be added later without a rewrite.
- Duplicate-photo protection IS in v1 (see below).
- KPI scoring: role-specific score sheets (max 100 points), bonus/penalty thresholds are CONFIGURABLE per
  role template (e.g. chef: 96-100 => +20%, <=85 => -20%; line cooks: +10% / -5% / -10%).
  The system shows a recommendation only. It NEVER calculates or pays salary.
- UI languages: Russian and Uzbek (i18n from the start; Russian is default).

## Tech stack
- Backend: **Kotlin + Spring Boot** (latest stable — check current versions, do not guess), JDK 21, Gradle Kotlin DSL.
  Java is allowed inside the same module where it is clearly simpler.
- DB: PostgreSQL + Flyway migrations (never edit an applied migration; add a new one).
- API: REST, contract-first with OpenAPI (`docs/openapi.yaml` is the source of truth; mobile client is generated from it).
- Auth: employee joins with an invite code; Founder logs in with phone/email + password. JWT access + refresh tokens.
- Storage: photos on local disk behind a `StorageService` interface (so S3-compatible storage can replace it later).
  Serve photos only via short-lived signed URLs.
- Mobile (later phase): Kotlin Multiplatform. Shared: models, API client (Ktor), local DB (SQLDelight),
  offline upload queue, task state logic. Native per platform: camera (CameraX / AVFoundation), image compression,
  background upload. UI: Compose Multiplatform; if the photo feed is slow on iOS, iOS UI falls back to SwiftUI.
- Infra: one VPS located IN UZBEKISTAN, Docker Compose, Caddy (HTTPS), GitLab CI or GitHub Actions,
  Prometheus + Grafana + Uptime Kuma. NO Kubernetes/Istio/ArgoCD/EFK — deliberately out of scope.

## Repository layout (monorepo)
```
/backend      Spring Boot service
/mobile       KMP app (created in a later phase)
/infra        docker-compose, Caddyfile, CI, backup scripts
/docs         openapi.yaml, PROMPTS.md, decisions
```

## Domain model (v1)
Organization -> Branch -> Unit (подразделение: Kitchen, Hall, Administrator, Waiters, custom) -> Employee.
- Every table carries `organization_id`. Every query is scoped by it. Add tests that prove tenant isolation.
- ChecklistTemplate (per Unit/role) -> ChecklistItem (question, `photo_required`, `points`, `critical`).
- Schedule: recurring slots (daily times, every-other-day, weekly zones). A scheduler generates `TaskInstance`
  rows with `due_at`. Timezone is `Asia/Tashkent` (UTC+5, no DST) for schedule logic; store timestamps in UTC.
- TaskInstance -> Submission (answers + photos) -> Review (accepted/rejected, comment).
- ExtensionRequest: employee asks BEFORE the deadline (reason, proposed new date); Founder or Branch Manager
  approves/rejects/counters; if nobody answers in N hours it escalates to the Branch Manager.
  Limits (configurable): 1 extension per task, max K per employee per month, not allowed for `critical` items.
- A server job marks `PENDING` tasks as `MISSED` at `due_at`. Client clocks are never trusted.
- AuditLog: append-only record of status changes, reviews, extension decisions.
- Roles: FOUNDER, BRANCH_MANAGER, EMPLOYEE. Role checks live in the service layer and are tested.

## Duplicate / fake photo protection (v1)
- On upload compute SHA-256 and a perceptual hash (implement dHash 64-bit ourselves, no heavy dependency).
- Compare with photos of the SAME task in the SAME branch from the previous 60 days.
  Exact match or Hamming distance <= configurable threshold (start with 6) => reject with HTTP 409 `DUPLICATE_PHOTO`.
- Record every blocked attempt (employee, task, time). The attempt count is itself a signal shown to the Founder.
- Limitation to remember: a re-photo of a screen or a new angle of the old scene passes; that is why the Founder review exists.
- Server time is authoritative. The client burns a watermark (branch, task, time, employee) into the image; never trust EXIF.

## Legal / data rules
- Personal data of Uzbek citizens (names, phones, photos) must be stored on servers physically in Uzbekistan and the
  database must be registered. Never send personal data to foreign services. No AWS/GCP for production data.
- No personal data in logs. No secrets in git (use env vars / `.env` ignored by git; commit `.env.example` only).

## Working rules for Claude Code
1. For any non-trivial task: propose a short plan first, wait for approval, then implement.
2. Small, focused commits. Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `test:`).
3. Every feature ships with tests: unit tests + integration tests using Testcontainers (real PostgreSQL). No H2.
4. Update `docs/openapi.yaml` before or together with the endpoint code.
5. Do not add dependencies without saying why. Prefer the standard library / Spring.
6. Keep things simple. No microservices, no message brokers, no caching layers until measured need.
7. After each task run the build and tests and report the actual results; never claim something passed without running it.
8. If a requirement here is ambiguous, ask ONE precise question instead of guessing.

## UX principles (for the mobile phase)
- Minimal and fast: one primary action per screen, large tap targets, system fonts, two colors max plus status colors.
- Employee flow: open app -> today's tasks -> tap task -> Yes/No -> camera -> send. Works offline, uploads later.
- Founder flow: dashboard by branch (done / missed / extended, overdue in red) -> review queue with swipe
  accept/reject and quick reject reasons -> extension requests. Show the previous photo of the same task next to the new one.

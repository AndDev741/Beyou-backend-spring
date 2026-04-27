# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
./mvnw spring-boot:run                    # Run on port 8099 (Undertow, not Tomcat)
./mvnw test                               # Run all tests
./mvnw test -Dtest=ClassName              # Run single test class
./mvnw test -Dtest=ClassName#methodName   # Run single test method
./mvnw package -DskipTests                # Build JAR without tests
```

Tests use the `test` profile (`application-test.yml`) with an H2 in-memory database. Controller tests use `@SpringBootTest` + `@AutoConfigureMockMvc(addFilters = false)` + `@ActiveProfiles("test")` with `@MockBean` for service layer mocking.

E2E tests (Playwright, in `../Beyou-e2e-tests/`) run against a real backend booted with `SPRING_PROFILES_ACTIVE=e2e` and a dedicated `beyou_e2e` Postgres database. `E2eSafetyCheck` refuses to start the backend in the `e2e` profile unless the JDBC URL contains `e2e` or `test`, so a misconfigured override can't silently wipe dev data (the e2e profile uses `ddl-auto: create-drop`).

Surefire is configured with special JVM args for Mockito (`-Dmockito.mock-maker=mock-maker-subclass`, `-XX:+EnableDynamicAgentLoading`).

## Architecture

**Java 21, Spring Boot 3.5, Undertow** (Tomcat excluded), virtual threads enabled, Lombok throughout, PostgreSQL in production (port 5490), H2 for unit tests, Postgres `beyou_e2e` for E2E.

### Package Structure

Base package: `beyou.beyouapp.backend`

| Package | Purpose |
|---------|---------|
| `controllers/` | REST controllers. Domain controllers at root, docs controllers under `controllers/docs/` |
| `domain/category/` | Category entity with XP leveling, `XpByLevel` lookup table |
| `domain/habit/` | Habit entity, linked to categories via `@ManyToMany` |
| `domain/task/` | Task entity, linked to categories via `@ManyToMany` |
| `domain/goal/` | Goal entity with status (`GoalStatus`) and term (`GoalTerm`) enums |
| `domain/routine/` | Polymorphic routine system (see below) |
| `domain/common/` | Shared: `XpProgress` (embeddable), `XpCalculatorService`, `RefreshUiDtoBuilder`, DTOs for UI refresh |
| `security/` | JWT auth (`TokenService`, `SecurityFilter`), refresh tokens, password reset, `AuthenticatedUser` helper, `DocsImportSecretFilter`, `E2eSafetyCheck` |
| `user/` | `User` entity (implements `UserDetails`), `UserService`, Google OAuth |
| `exceptions/` | `GlobalExceptionHandler` (`@ControllerAdvice`), `BusinessException` + `ErrorKey` enum for domain errors |
| `docs/` | Docs import system: architecture, api, project, blog, search — pulls markdown from GitHub repo |
| `seed/` | `DatabaseSeeder` interface + `SeedOrchestrator` (CommandLineRunner) for startup data |
| `AOP/` | Aspect-based logging for controllers and service methods |
| `notification/` | `EmailService` for mail sending (password reset) |

### Key Patterns

**XP/Leveling system**: `XpProgress` is an `@Embeddable` used by `User`, `Category`, `Habit`, `Routine`. Level thresholds come from the `XpByLevel` table (seeded at startup). `XpCalculatorService` coordinates XP changes across user + entity + categories in a single transaction (`Propagation.MANDATORY`).

**Routine hierarchy**: `Routine` (abstract, `SINGLE_TABLE` inheritance) -> `DiaryRoutine` -> has `RoutineSection`s -> each section has `HabitGroup`s and `TaskGroup`s (both extend abstract `ItemGroup` with `JOINED` inheritance). `BaseCheck` (also `JOINED`) has child types `HabitGroupCheck` + `TaskGroupCheck`. `CheckItemService` handles check/uncheck/skip logic with XP and constance tracking.

**RefreshUiDTO**: After mutations (check/uncheck habits in routines, complete goals, etc.), the backend returns a `RefreshUiDTO` containing updated XP/level data for user, categories, habits, and the checked item — so the frontend can update all affected UI in one response. (Note: untyped on the frontend side — silent break risk on field renames.)

**Authentication flow**: JWT access token (15min, returned in the `X-Access-Token` response header) + refresh token (15 days, `httpOnly` cookie). `SecurityFilter` validates JWT on every request except public endpoints (`/auth/login`, `/auth/register`, `/auth/refresh`, `/auth/google`, `/auth/logout`, `/auth/forgot-password`, `/auth/reset-password/**`, `/auth/verify-email`, `/docs/**` except `/docs/admin/**`). `AuthenticatedUser` component extracts the current user from `SecurityContextHolder`. Profile `e2e` auto-verifies emails on registration (`e2e.auto-verify-email: true` in `UserService`).

**Docs import**: Protected by `DocsImportSecretFilter` (`X-Docs-Import-Secret` header). Fetches markdown files from a configurable GitHub repo, parses YAML frontmatter, and stores in DB.

**Error handling**: Domain errors use `BusinessException(ErrorKey, message)`. `GlobalExceptionHandler` maps exceptions to `ApiErrorResponse` with structured error keys that the frontend can match for i18n. AOP layer (`ServiceMethodsLogging`, `ControllerLogging`) logs expected client errors at WARN without stack traces — see `isExpectedClientError`.

### Entity Ownership

All domain entities (categories, habits, tasks, goals, routines) belong to a `User` via `@ManyToOne`. Services verify ownership before mutations and reads:
- `CategoryService.getCategory(id, userId)` — throws `CATEGORY_NOT_OWNED`
- `HabitService` — throws `HABIT_NOT_OWNED`
- `DiaryRoutineService.getDiaryRoutineById/ModelById/ByScheduleId(id, userId)` — throws `ROUTINE_NOT_OWNED`
- `GoalService.checkIfGoalIsFromTheUserInContext` — throws `GOAL_NOT_OWNED`

The `User` entity cascades deletes to all owned entities.

## Configuration

Config is in `application.yaml` (not `.properties`). Environment variables are required — see `envExample` for the full list. Key ones: `TOKEN_SECRET`, `GOOGLE_CLIENT_ID/SECRET`, `COOKIE_SECURE`, `CORS_ALLOWED_PATTERN`, `FRONTEND_URL`, `MAIL_*`.

The `docs.import.*` properties configure which GitHub repository to pull documentation from.

Profile overlays:
- `application-test.yml` — H2 in-memory for unit/integration tests
- `application-e2e.yml` — Postgres `beyou_e2e`, `ddl-auto: create-drop`, auto-verify emails, rate limit off
- `application-prod.yml` — `ddl-auto: validate`, CORS wildcard rejected, Swagger off, actuator localhost-only

## Known Issues & Security (re-checked 2026-05-24)

See full report: `../relatories/backend-deployment-readiness-report.md`

### Audit 001 — 26/26 fixed
- ~~Hardcoded DB password~~ → `${DATABASE_PASSWORD}` (no fallback in prod)
- ~~`ddl-auto: update` in prod~~ → `validate` in prod profile
- ~~Actuator fully exposed~~ → `health,metrics,prometheus`, localhost-bound
- ~~`server.adress` typo~~ → Fixed
- ~~Grafana anonymous admin~~ → Auth required
- ~~No rate limiting~~ → 4-tier Bucket4j
- ~~No security headers~~ → CSP, Referrer-Policy, Permissions-Policy
- ~~`isGoogleAccount` mass assignment~~ → Removed from `UserRegisterDTO`
- ~~Edit DTO validation bypass~~ → `@Min/@Max` + `@Valid` on controllers
- ~~CSP `connect-src 'self' *`~~ → Restricted to `'self' https://accounts.google.com https://www.googleapis.com`

### Audit 002 — backend items, re-checked 2026-05-24
- ~~`CategoryService.getCategory()` IDOR~~ ✅ Throws `CATEGORY_NOT_OWNED` (`CategoryService.java:50`)
- ~~`ScheduleService.update()` IDOR~~ ✅ Calls `getDiaryRoutineByScheduleId(scheduleId, userId)` which throws `ROUTINE_NOT_OWNED` (`ScheduleService.java:64`)
- ~~`CategoryEditRequestDTO` zero validation~~ ✅ `@NotBlank` + `@NotEmpty @Size(2..256)` + `@Size(max=1024)`
- ~~`CreateTaskRequestDTO` missing `@Min/@Max`~~ ✅ `@NotNull @Min(1) @Max(5)` on importance + difficulty
- ~~`GoalService.decreaseCurrentValue()` no floor guard~~ ✅ `Math.max(0, currentValue - 1)`
- ~~`ResetPasswordRequestDTO` `@Size(min=6)` vs 12-char policy~~ ✅ Now `@Size(min=12)`
- ~~`ServiceMethodsLogging` logs full args~~ ✅ Only logs `getArgs().length` (a count, no PII)
- ~~Docs search `limit` no `@Max`~~ ✅ `@Max(100)`

### Still open
- **`CreateGoalRequestDTO` accepts user-controlled `currentValue` / `status`** — low impact: an attacker can POST a "fake-completed" goal, but no XP is awarded because XP only flows through `PUT /goal/complete`. Cosmetic data integrity issue; consider dropping these fields from the create DTO and defaulting them server-side.
- **BCrypt cost factor 10** — OWASP recommends 12. Low priority.
- **`DOCS_IMPORT_SECRET=something`** — replace with a 32+ char random secret.
- **No HTTPS/TLS in production docker-compose** — terminate at a reverse proxy in prod.

### Recently fixed (2026-05-23, during E2E test work)
- **`HabitGroupCheck.@UniqueConstraint`** referenced `check_date`, a column that lives on the parent `base_check` table due to `JOINED` inheritance. Schema DDL silently failed at startup → the `habit_group_check` table was never created → `GET /routine` 500'd (and Spring escalated to 403 with empty body) the moment any habit-group had checks to load. Constraint removed; uniqueness-per-day belongs at the service layer. Surfaced by `Beyou-e2e-tests/tests/routine-checkin.spec.ts`.
- **`DiaryRoutineMapper`** leaked lazy `habitGroupChecks` / `taskGroupChecks` collection proxies into the response DTO. Jackson serializes after the transaction commits, so it hit `LazyInitializationException`. Now wraps with `new ArrayList<>(...)` inside the mapper so the collection is materialized while the session is still open.

### CI / scanning
- GitHub Actions: `ci.yml` (build + tests), `codeql.yml` (Java SAST), `security-scan.yml` (OWASP Dependency-Check; needs `NVD_API_KEY` secret for non-rate-limited scans)

### Known design quirks (do not change without understanding)
- `XpCalculatorService` uses `Propagation.MANDATORY` — must always be called within an existing transaction
- Refresh token format is `{UUID}.{rawToken}`; stored as BCrypt hash; comparison is always hash-based
- `AuthenticatedUser` is a request-scoped Spring component, not a static helper
- `UserCacheEvictService` evicts ALL user caches on any write — intentionally broad for simplicity
- `CategoryRepository.findByUserId()` returns single Optional — likely dead code; `findAllByUserId` is the correct query
- `@CachePut`/`@CacheEvict` only work on public methods called through the Spring proxy (external calls); internal `this.method()` calls bypass the cache
- Tests bypass security filters (`addFilters = false` in MockMvc) and mock the service layer — they test HTTP binding, not business logic integration
- `application.yaml` has a commented `# ddl-auto: create-drop` — never uncomment in any environment with real data
- `GoalController PUT /goal/increase|decrease|complete` accepts a raw UUID as request body. Jackson deserializes from a JSON-encoded string (`"<uuid>"`), NOT a bare UUID. External clients (E2E `apiClient`) must `JSON.stringify(uuid)` or send the quoted form; the bare UUID returns 403.
- `RoutineSnapshotScheduler` runs per-timezone — bottleneck at scale with many distinct user timezones
- HikariCP pool at default 10 connections; configure explicitly for production load
- `e2e` profile uses `ddl-auto: create-drop` — `E2eSafetyCheck` enforces that the JDBC URL contains `e2e` or `test`, so a misconfigured override can't wipe the dev `beyou` database

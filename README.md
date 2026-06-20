# Beyou Backend

> REST API for **Beyou** — a personal productivity app for habits, goals, routines, tasks, and categories, with built-in XP/leveling gamification and AI-assisted routine generation.

[![CI](https://github.com/AndDev741/Beyou-backend-spring/actions/workflows/ci.yml/badge.svg)](https://github.com/AndDev741/Beyou-backend-spring/actions/workflows/ci.yml)
[![CodeQL](https://github.com/AndDev741/Beyou-backend-spring/actions/workflows/codeql.yml/badge.svg)](https://github.com/AndDev741/Beyou-backend-spring/actions/workflows/codeql.yml)
[![Security Scan](https://github.com/AndDev741/Beyou-backend-spring/actions/workflows/security-scan.yml/badge.svg)](https://github.com/AndDev741/Beyou-backend-spring/actions/workflows/security-scan.yml)
[![Java 25](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

Beyou helps people build better days: track habits, set goals, plan daily routines, and earn XP as they make progress. This repository is the **Spring Boot backend** that powers the web and mobile clients.

## Features

- **Domain model for productivity** — categories, habits, tasks, goals, and a polymorphic routine system with daily routines, sections, and item groups.
- **Gamification engine** — XP and leveling for the user, each category, and each habit, coordinated transactionally so a single check-in updates every affected entity in one response.
- **Daily routines & check-ins** — schedule routines, check/uncheck/skip items, track streaks ("constance"), and persist per-day snapshots.
- **AI routine generation** — describe a routine in natural language and get a structured draft (Spring AI, provider-agnostic), then confirm it to atomically create categories, habits, tasks, and the routine.
- **Authentication** — email/password and Google OAuth, JWT access tokens, refresh-token rotation, email verification, and password reset.
- **Production hardening** — per-endpoint rate limiting (Bucket4j), Caffeine caching, security headers/CSP, ownership checks (IDOR-safe), and structured i18n-friendly error keys.
- **Observability** — Actuator + Prometheus metrics on a separate, localhost-bound management port.
- **Live docs import** — pulls architecture/API/blog/project markdown from a GitHub repo and serves it through the API.

## Tech stack

| Area | Choice |
|------|--------|
| Language / runtime | Java 25 |
| Framework | Spring Boot 4 (Spring MVC, virtual threads enabled) |
| Persistence | Spring Data JPA / Hibernate, PostgreSQL |
| Security | Spring Security, JWT (`java-jwt`), Google OAuth |
| AI | Spring AI (`ChatClient`, OpenAI starter — provider-agnostic) |
| Caching | Spring Cache + Caffeine |
| Rate limiting | Bucket4j |
| API docs | springdoc OpenAPI / Swagger UI |
| Mail | Spring Mail (password reset, verification) |
| Testing | JUnit 5, Mockito, Spring Security Test, **Testcontainers** (PostgreSQL) |
| Build | Maven, Lombok, OWASP Dependency-Check |

## Getting started

### Prerequisites

- **JDK 25**
- **Maven 3.9+** (a `mvnw.cmd` wrapper is provided for Windows; on Linux/macOS use a system `mvn`)
- **PostgreSQL** (locally expected on port `5490`, database `beyou`) — or use the Docker setup below
- **Docker** — required to run the test suite (Testcontainers boots a throwaway PostgreSQL)

### Configuration

Configuration lives in `application.yaml` and is driven entirely by environment variables. Copy `envExample` to a `.env` (or export the variables) and fill in the blanks.

> [!IMPORTANT]
> `TOKEN_SECRET`, `DATABASE_PASSWORD`, and the Google/mail credentials have no safe defaults. Set them before starting the app. Never commit real secrets — use environment variables or a secret manager.

| Variable | Purpose | Default |
|----------|---------|---------|
| `SPRING_PROFILES_ACTIVE` | Active profile (`dev`, `prod`, `test`, `e2e`) | `dev` |
| `DATABASE_URL` | JDBC URL | `jdbc:postgresql://localhost:5490/beyou` |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | DB credentials | `postgres` / — |
| `TOKEN_SECRET` | JWT signing secret | — |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Google OAuth | — |
| `FRONTEND_URL` | Allowed redirect / link base | `http://localhost:3000/` |
| `COOKIE_SECURE` / `COOKIE_SAME_SITE` | Refresh-cookie flags | `false` / `Lax` |
| `CORS_ALLOWED_PATTERN` | Allowed CORS origin pattern (wildcard rejected in `prod`) | `*` |
| `MAIL_*` | SMTP host/port/credentials for transactional email | — |
| `AI_API_KEY` / `AI_ROUTINE_MODEL` / `AI_ROUTINE_ENABLED` | AI routine generation | — / `gpt-5-mini` / `true` |
| `DOCS_IMPORT_*` | GitHub repo + secret for docs import | see `envExample` |
| `MANAGEMENT_PORT` / `ACTUATOR_ENDPOINTS` | Actuator server | `9091` / `health,metrics,prometheus` |

See [`envExample`](envExample) for the full list.

### Run

```bash
# Linux / macOS (system Maven)
mvn spring-boot:run        # starts on http://localhost:8099

# Windows
mvnw.cmd spring-boot:run
```

Build a runnable JAR:

```bash
mvn package -DskipTests
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

### Run with Docker

The multi-stage [`Dockerfile`](Dockerfile) provides `dev` (hot reload via `spring-boot:run`) and `runtime` (slim JRE, non-root) targets, both exposing `8099` (app) and `9091` (management).

```bash
docker build --target runtime -t beyou-backend .
docker run -p 8099:8099 -p 9091:9091 --env-file .env beyou-backend
```

> [!TIP]
> For a full local stack (PostgreSQL + backend + frontend with hot reload), use the orchestration scripts in the sibling `Beyou-dev-env` repository (`./scripts/up-dev.sh`).

## API

All endpoints are served under the `/api/v1` context path, e.g.:

```
POST http://localhost:8099/api/v1/auth/login
```

The Actuator/management server runs separately on port `9091` and is **not** versioned — `/actuator/health` stays at the root. Swagger UI is available in non-production profiles at `/api/v1/swagger-ui/index.html`.

### Endpoint groups

| Base path | Responsibility |
|-----------|----------------|
| `/auth` | Register, login, refresh, logout, Google OAuth, email verification, forgot/reset password |
| `/user` | Profile; `/user/export` for data export |
| `/category` | Categories with XP/leveling |
| `/habit` | Habits linked to categories |
| `/task` | Tasks linked to categories |
| `/goal` | Goals (increase / decrease / complete — only `complete` awards XP) |
| `/routine`, `/schedule`, `/snapshot` | Daily routines, scheduling, and per-day snapshots |
| `/ai/routine` | `POST /generate` (stateless draft) and `POST /confirm` (transactional create) |
| `/docs/**` | Architecture, API, blog, project docs, and search (admin import behind a secret header) |

### Authentication flow

- **Access token** — short-lived JWT (15 min), returned in the `X-Access-Token` response header and sent on each request.
- **Refresh token** — long-lived (15 days). Web clients receive it as an `httpOnly` cookie; mobile clients use the `X-Client` / `X-Refresh-Token` header transport.
- `SecurityFilter` validates the JWT on every request except the public auth and docs endpoints. Domain services verify entity ownership before any read or mutation.

## Testing

```bash
mvn test                                   # all tests (needs Docker for Testcontainers)
mvn test -Dtest=ClassName                  # single test class
mvn test -Dtest=ClassName#methodName       # single test method
```

Unit/controller tests run under the `test` profile against a Testcontainers PostgreSQL instance. Controller tests use `@SpringBootTest` + MockMvc with the service layer mocked, exercising HTTP binding rather than full integration.

End-to-end tests (Playwright) live in the sibling `Beyou-e2e-tests` repository and drive the full stack against a dedicated `beyou_e2e` database.

> [!CAUTION]
> The `e2e` profile uses `ddl-auto: create-drop`. `E2eSafetyCheck` refuses to start unless the JDBC URL contains `e2e` or `test`, so a misconfigured override can't wipe development data.

## Project structure

```
src/main/java/beyou/beyouapp/backend/
├── controllers/        REST controllers (domain + docs/)
├── domain/             category, habit, task, goal, routine, ai, common
├── security/           JWT, refresh tokens, password reset, rate limiting
├── user/               User entity (UserDetails), service, Google OAuth
├── docs/               GitHub-backed docs import (architecture, api, blog, project, search)
├── exceptions/         GlobalExceptionHandler + BusinessException / ErrorKey
├── notification/       EmailService
├── AOP/                Controller & service logging aspects
├── seed/               Startup data seeders
└── config/             Cross-cutting configuration
```

### Profiles

| Profile | Database | `ddl-auto` | Notes |
|---------|----------|-----------|-------|
| `dev` | PostgreSQL `beyou` | `update` | Local development |
| `prod` | PostgreSQL | `validate` | CORS wildcard rejected, Swagger off, actuator localhost-only |
| `test` | Testcontainers PostgreSQL | managed | Unit/integration tests |
| `e2e` | PostgreSQL `beyou_e2e` | `create-drop` | Auto-verifies emails, rate limiting off |

## Related repositories

Beyou is split across several repositories:

- **Beyou-backend-spring** — this repo (Spring Boot API)
- **Beyou-Frontend** — React + Vite web client
- **Beyou-e2e-tests** — Playwright end-to-end suite
- **Beyou-dev-env** — Docker Compose orchestration for local development
- **Beyou-arch-design** — OpenAPI specs and architecture/design docs (bilingual)

## License

Apache License 2.0 — see [LICENSE](LICENSE).

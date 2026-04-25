# Beyou Backend

[![CI](https://github.com/AndDev741/Beyou-backend-spring/actions/workflows/ci.yml/badge.svg)](https://github.com/AndDev741/Beyou-backend-spring/actions/workflows/ci.yml)
[![CodeQL](https://github.com/AndDev741/Beyou-backend-spring/actions/workflows/codeql.yml/badge.svg)](https://github.com/AndDev741/Beyou-backend-spring/actions/workflows/codeql.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

REST API for Beyou — a personal productivity app for habits, goals, routines, tasks, and categories with built-in gamification.

## Stack

- **Java 25** + **Spring Boot 3.5** (Undertow, virtual threads enabled)
- **PostgreSQL 15** in production, **Testcontainers PostgreSQL** in tests
- **JWT** auth (15 min access, 15 day refresh) + **Google OAuth**
- **Bucket4j** rate limiting
- **Caffeine** caching
- **JPA/Hibernate**, Lombok, MapStruct
- **Bilingual** error keys for frontend i18n

## Quick start

```bash
./mvnw spring-boot:run     # run on port 8099
./mvnw test                # run all tests (requires Docker for Testcontainers)
./mvnw package -DskipTests # build JAR
```

Configuration via `application.yaml` + environment variables (see `envExample`).

## License

Apache License 2.0 — see [LICENSE](LICENSE).

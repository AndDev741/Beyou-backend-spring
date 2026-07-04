# Flyway Cutover — What Shipped and What You Must Do

**Status**: Implemented on branch `feat/flyway-adoption` (2026-07-03).
**Supersedes parts of**: [implementation-plan.md](implementation-plan.md) (2026-04-24) — see "Superseded decisions" below.

## What shipped

| Piece | Where |
|---|---|
| Flyway deps (Boot 4 module + core + postgres) | `pom.xml` |
| `V1__baseline.sql` — schema forged in an ephemeral container | `src/main/resources/db/migration/` |
| `V2__add_supporting_indexes.sql` — evidence-derived indexes | `src/main/resources/db/migration/` |
| `R__seed_xp_by_level.sql` — XpByLevel reference data (replaces the seed package) | `src/main/resources/db/migration/` |
| Baseline factory (re-runnable generator) | `scripts/generate-baseline.sh` + `BaselineGeneratorTest` |
| `ddl-auto: validate` everywhere; `${DDL_AUTO:update}` removed | `application*.yaml` |
| `SchemaOwnershipGuard` — refuses boot if Flyway + mutating ddl-auto | `security/SchemaOwnershipGuard.java` |
| `SchemaIndexParityTest` — declared `@Index`/`@UniqueConstraint` must exist in the migrated schema | `src/test/.../integration/schema/` |
| CI migration checks (immutability tripwire, entity↔migration correlation, squawk lint) | `.github/workflows/ci.yml` |
| Entity hygiene: explicit `@Table` names, `@DiscriminatorColumn`, `GenerationType.UUID`, PK `unique=true` duplicates removed | domain/user/security entities |

## ⚠️ Manual steps: reset the pre-cutover databases

Any database built by the old `ddl-auto` regime has no `flyway_schema_history`,
and the first boot after this branch will **fail** against it (Flyway refuses a
non-empty schema without a baseline). Both are throwaway by design — reset once:

1. **Dev `beyou`:**
   ```bash
   cd ../Beyou-dev-env && ./scripts/reset-db.sh
   ```
2. **Local e2e `beyou_e2e`** (lives on the same long-lived Postgres at
   localhost:5490; a killed pre-cutover run leaves Hibernate tables behind):
   ```bash
   docker compose -f ../Beyou-dev-env/docker-compose.dev.yml exec postgres \
     psql -U postgres -c "DROP DATABASE IF EXISTS beyou_e2e;" -c "CREATE DATABASE beyou_e2e;"
   ```
   Also note the semantics change: local e2e runs no longer wipe data per boot
   (that was `create-drop`); data accumulates until you reset. The CI compose
   stack is unaffected — it provisions a fresh Postgres container per run.

Then start the backend normally: Flyway runs `V1 → V2 → R__seed_xp_by_level`
and Hibernate validates. Unit/integration tests (Testcontainers) are fresh per
JVM run — nothing to do there.

## Superseded decisions from the original plan

| Plan said (2026-04-24) | What shipped instead | Why |
|---|---|---|
| Disable Flyway in the `test` profile (tests use H2) | Flyway + `ddl-auto: validate` in the test profile | Tests moved to Postgres 15 Testcontainers after the plan was written; every integration test is now a drift gate |
| Generate V1 by mutating `application.yaml` and dumping the dev DB (10 manual steps) | `scripts/generate-baseline.sh` → ephemeral container | Reproducible; can't inherit dev-DB drift; no config edits to forget |
| V2 indexes on `habits`/`tasks`/`refresh_tokens`/`diary_routine` | Table names verified against the generated baseline | 5 of the plan's 9 statements targeted tables that don't exist (`habit`/`task`/`refresh_token` were singular before the hygiene PR; `diary_routine` was never a table — SINGLE_TABLE puts DiaryRoutine rows in `routines`) |
| (not in plan) | ~~Unique index on `refresh_tokens(token_hash)`~~ — **dropped after code review** | The ideation claimed `findByTokenHash` was the hottest auth query; review proved it dead code (zero callers — `token_hash` is salted BCrypt, unusable as a lookup key; the refresh flow uses `findById`). The dead method was deleted instead of indexed |
| (not in plan) | Index on `users(timezone)` | `RoutineSnapshotScheduler` batches by timezone (`findAllByTimezone` — verified real); the notification roadmap will lean on it |
| `XpByLevelSeeder` (count-guarded CommandLineRunner) | `R__seed_xp_by_level.sql` with `ON CONFLICT DO UPDATE` | The count guard meant an edited threshold silently never reached a populated DB; the repeatable migration re-applies on change |
| e2e keeps `create-drop` | e2e boots via Flyway | Otherwise e2e (the gate before GHCR publish) certifies a schema path production never takes |

## Immutability policy

- **Until first production deploy**: the chain may be squashed into a
  regenerated V1 via `scripts/generate-baseline.sh`. Use the `migration-rewrite`
  PR label (the CI tripwire blocks edits otherwise — the label is the enforced
  control) and tag the repo before squashing (manual discipline, not enforced).
- **After first production deploy**: versioned migrations are immutable.
  Fix forward with a new `V<n>`; recover checksum accidents with `flyway repair`.
- `R__` (repeatable) migrations are *meant* to change — the tripwire only
  guards `V*.sql`.

## Known residuals (accepted at code review, 2026-07-03)

- `SchemaOwnershipGuard`/`E2eSafetyCheck` boot ordering relies on the
  established component-scan-before-JPA convention, not an enforced dependency
  edge (an `EnvironmentPostProcessor` would be the hard guarantee). Unit-tested
  decision table; accepted as consistent with the existing pattern.
- The guard watches `spring.jpa.hibernate.ddl-auto` only; the native spelling
  `spring.jpa.properties.hibernate.hbm2ddl.auto` would bypass it. No such
  override exists in the repo.
- The CI migration checks run on `pull_request` only — direct pushes to main
  bypass them (branch protection is the durable fix).
- `BaselineGeneratorTest` never runs in CI (system-property gated); drift in
  its pg_dump cleanup surfaces at the next manual squash.
- Post-prod edits to `R__seed_xp_by_level.sql` reach populated DBs by design,
  but nothing rebalances denormalized `XpProgress` snapshots on users/habits —
  a threshold change after launch needs a paired `V<n>` data migration.
- `../Beyou-e2e-tests/README.md` (external repo) still documents the old
  create-drop wipe-per-boot semantics — update it there.

## Deliberately deferred (revisit at first prod deploy)

- `CREATE INDEX CONCURRENTLY` + removing squawk's
  `require-concurrent-index-creation` and `require-timeout-settings` exclusions —
  pointless on empty tables, mandatory on populated ones.
- Running migrations as a separate deploy step (vs. on app boot) — only
  matters with rolling/multi-replica deploys; today's single-container compose
  boot is fine.
- Migrate-with-data rehearsal fixtures — no real data exists yet.

## Cutover acceptance checklist

- [x] `mvn verify` green with Flyway-owned schema (validate everywhere)
- [x] `SchemaIndexParityTest` proves declared indexes exist
- [ ] Dev DB reset + backend boots clean (manual — see above)
- [ ] First real destructive change (e.g. a column rename) ships as a `V3`
      migration against a data-bearing dev DB and the data survives —
      retiring `reset-db.sh` as the schema-change tool

# Flyway Adoption + Index Strategy — Implementation Plan

**Status**: Planned, not yet implemented
**Context**: Beyou backend (Spring Boot 3.5, Postgres 15, Hibernate with `ddl-auto: update` in dev, `validate` in prod)
**Goal**: Introduce versioned schema migrations so we can manage indexes (and future schema changes) safely, then add index coverage for high-traffic query paths.

---

## 1. Why Flyway

### Why we need a migration tool at all

Currently Hibernate manages the schema via `ddl-auto`:

- **Dev (`update`)**: adds new tables/columns from entity annotations, but **never drops, renames, or reliably reconciles indexes**. The dev schema slowly drifts from what the annotations declare.
- **Prod (`validate`)**: only checks that tables/columns exist. **It does not validate indexes at all** — an `@Index(columnList = "user_id")` annotation on an entity is effectively a comment in production. The index never gets created.

Consequence: we cannot reliably add indexes, rename columns, add constraints, or backfill data using entity annotations alone. We need a separate, explicit, versioned layer for schema evolution.

### Why Flyway over Liquibase

| Criterion | Flyway | Liquibase |
|---|---|---|
| Migration format | Plain SQL (`.sql` files) | XML / YAML / JSON changelog DSL |
| Learning curve | Zero — you already know SQL | New DSL to learn |
| DB-agnostic | No (SQL is Postgres-specific) | Yes |
| Rollback | Manual (write a down migration) | Auto-rollback for many changes |
| Spring Boot integration | First-class, auto-run on startup | First-class, auto-run on startup |
| Best fit for | Single DB engine, small/solo teams | Multi-DB or enterprise teams |

**Decision**: Flyway.

Reasoning:
- We are Postgres-only and will stay Postgres-only.
- Plain SQL is easier to review, grep, and reason about than a DSL.
- Rollback via a new forward migration is fine at our scale — we don't need automated rollbacks.
- Solo/small-team context: the simpler tool wins.

---

## 2. Starting Strategy — "Dev-Only Reset" (chosen)

**This app has never been deployed to production.** The data in the dev database is throwaway (seed data + test accounts). That makes the starting strategy much simpler than the usual "baseline an existing prod DB" dance.

### Chosen approach: drop + regenerate from entities, then snapshot as `V1`

Steps:
1. Drop the dev database entirely.
2. Start the app **once** with `ddl-auto: create` so Hibernate generates a pristine schema from the current entities.
3. Dump that schema with `pg_dump --schema-only --no-owner --no-privileges` → this becomes `V1__baseline.sql`.
4. From now on: `ddl-auto: validate` in **every** environment (including dev), and Flyway owns all schema changes going forward.

### Why not "baseline an existing schema"

The usual approach for a live DB is to enable `spring.flyway.baseline-on-migrate: true` and mark the current schema as already-applied without generating SQL. We could do that here, but:

- The current dev schema was built incrementally by `ddl-auto: update` across many entity edits. It may have leftover columns, missing indexes that annotations declare, or subtly different types vs. a fresh generation.
- Starting from a clean, regenerated schema guarantees `V1__baseline.sql` matches the entities exactly — no drift on day one.
- No production data to preserve means the cost of dropping is zero.

### Why not "let Flyway run against the current dev DB and generate from there"

Same drift concern. A regenerated schema is cleaner than the accumulated history of `ddl-auto: update`.

---

## 3. Implementation Steps

### Phase 1 — Add Flyway infrastructure

1. **Add dependency** to `pom.xml`:
   ```xml
   <dependency>
       <groupId>org.flywaydb</groupId>
       <artifactId>flyway-core</artifactId>
   </dependency>
   <dependency>
       <groupId>org.flywaydb</groupId>
       <artifactId>flyway-database-postgresql</artifactId>
   </dependency>
   ```

2. **Configure** in `application.yaml`:
   ```yaml
   spring:
     flyway:
       enabled: true
       locations: classpath:db/migration
       baseline-on-migrate: false   # we start clean, no baseline needed
       validate-on-migrate: true
     jpa:
       hibernate:
         ddl-auto: validate         # Flyway owns the schema now
   ```

   Note: `application-test.yml` uses H2, which needs a different strategy. Options:
   - Keep H2 on `ddl-auto: create-drop` and disable Flyway for the `test` profile (`spring.flyway.enabled: false`). Simplest.
   - Or switch tests to a Postgres Testcontainer and run Flyway against it. Better fidelity, more setup. Defer to later.

   **Decision for now**: disable Flyway in the `test` profile. Tests already use H2 in-memory and mock the service layer — they don't exercise real schema behavior.

3. **Create migration folder**: `src/main/resources/db/migration/`

### Phase 2 — Generate the baseline

1. Stop the running backend.
2. Drop and recreate the dev database:
   ```bash
   docker compose -f Beyou-dev-env/docker-compose.dev.yml exec postgres \
     psql -U <user> -c "DROP DATABASE beyou; CREATE DATABASE beyou;"
   ```
3. Temporarily set `ddl-auto: create` in `application.yaml`.
4. Start the backend — Hibernate generates the full schema from entities.
5. Stop the backend.
6. Dump the schema:
   ```bash
   docker compose -f Beyou-dev-env/docker-compose.dev.yml exec postgres \
     pg_dump -U <user> --schema-only --no-owner --no-privileges beyou \
     > src/main/resources/db/migration/V1__baseline.sql
   ```
7. Review `V1__baseline.sql`:
   - Remove any `CREATE SCHEMA public` / `COMMENT ON SCHEMA` noise.
   - Remove `SET` statements Flyway doesn't need.
   - Keep: `CREATE TABLE`, `ALTER TABLE ... ADD CONSTRAINT`, `CREATE INDEX`, `CREATE SEQUENCE`.
8. Switch `ddl-auto: validate` permanently.
9. Drop the DB again and restart the backend — Flyway should now run `V1__baseline.sql` from scratch, then Hibernate validates successfully.
10. Re-run seed (`SeedOrchestrator` handles this on startup) and smoke-test the app.

### Phase 3 — Add the first real migration (indexes)

Create `V2__add_user_id_and_supporting_indexes.sql`:

```sql
-- User-scoped lookups on domain tables (every dashboard load)
CREATE INDEX IF NOT EXISTS idx_habits_user_id      ON habits(user_id);
CREATE INDEX IF NOT EXISTS idx_tasks_user_id       ON tasks(user_id);
CREATE INDEX IF NOT EXISTS idx_goals_user_id       ON goals(user_id);
CREATE INDEX IF NOT EXISTS idx_categories_user_id  ON categories(user_id);
CREATE INDEX IF NOT EXISTS idx_diary_routine_user  ON diary_routine(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user ON refresh_tokens(user_id);

-- Latest-per-user password reset token (findTopByUserIdOrderByCreatedAtDesc)
CREATE INDEX IF NOT EXISTS idx_password_reset_user_created
  ON password_reset_tokens(user_id, created_at DESC);

-- Email verification lookup
CREATE INDEX IF NOT EXISTS idx_users_verification_token
  ON users(verification_token)
  WHERE verification_token IS NOT NULL;  -- partial index, most users are verified

-- Foreign-key lookup used by DiaryRoutineRepository.findByScheduleId
CREATE INDEX IF NOT EXISTS idx_diary_routine_schedule
  ON diary_routine(schedule_id);
```

Note on table names: verify the actual generated names after Phase 2 (Hibernate's naming strategy may produce `diary_routines` or similar). Adjust the migration to match.

Note on `CREATE INDEX CONCURRENTLY`: **not needed in dev**. When we eventually deploy to prod, any index on a non-trivial table should use `CONCURRENTLY` to avoid locking writers. `CONCURRENTLY` cannot run inside a transaction, so that migration will need:
```sql
-- ${flyway:executeInTransaction:false}
CREATE INDEX CONCURRENTLY ...
```
Defer that until first production deploy.

### Phase 4 — Validate

1. Drop the dev DB and restart — Flyway runs `V1` then `V2`, Hibernate validates.
2. Run `\d+ habits` in psql and confirm `idx_habits_user_id` exists.
3. `EXPLAIN ANALYZE SELECT * FROM habits WHERE user_id = '<some-uuid>';` — expect `Index Scan using idx_habits_user_id`.
4. Run the test suite to confirm nothing broke.

---

## 4. Rollback / Recovery

If Phase 2 goes wrong:
- Nothing in prod to recover — dev data is disposable.
- Revert the `pom.xml` + `application.yaml` changes, delete `V1__baseline.sql`, flip `ddl-auto` back to `update`, drop/recreate DB.

If a later migration goes wrong:
- Write a compensating `V(n+1)__revert_xxx.sql`. Do **not** edit an already-applied migration file — Flyway detects that by checksum and refuses to run.
- For dev emergencies only, `DELETE FROM flyway_schema_history WHERE version = 'X'` can un-apply a migration, but never do this in any environment with real data.

---

## 5. Conventions (for future migrations)

- **File name**: `V{version}__{snake_case_description}.sql`. Version is a monotonically increasing integer or dotted number (`V2`, `V3`, `V3.1`).
- **One logical change per migration**. "Add index on X" and "rename column Y" are two files, not one.
- **Immutable**: once a migration is committed to `main`, never edit it. Write a new one.
- **Guard with `IF NOT EXISTS`** for `CREATE INDEX` so re-running against partially-migrated envs is safe.
- **Always test locally** by dropping + rebuilding the dev DB before merging a migration.
- **Production-safe DDL**:
  - `CREATE INDEX CONCURRENTLY` for any table that might have meaningful row counts.
  - `ALTER TABLE ... ADD COLUMN ... DEFAULT x` is a rewrite on Postgres < 11; on 15 a non-volatile default is metadata-only. Still, test on realistic data volume before shipping.
  - Avoid `ALTER TABLE ... ALTER COLUMN TYPE` on large tables without a staged plan.

---

## 6. What this plan does NOT cover (future work)

- Moving tests onto Postgres Testcontainers so migrations are exercised in CI.
- Wiring Flyway into CI (dry-run `flyway validate` on PRs).
- Partial/functional indexes for specific query shapes (e.g., `WHERE status = 'ACTIVE'`).
- Removing redundant `@Column(unique=true)` on PK columns — those create duplicate unique indexes. Cleanup candidate but not urgent.
- Dropping the `CategoryRepository.findByUserId()` single-Optional method (likely dead code per `CLAUDE.md`).

---

## 7. Decision log

| Date | Decision | Rationale |
|---|---|---|
| 2026-04-24 | Adopt Flyway over Liquibase | Single DB engine, prefer plain SQL, simpler tool for small team |
| 2026-04-24 | Clean-slate baseline (drop + regen) instead of baselining existing schema | No prod data to preserve; avoids drift between annotations and schema |
| 2026-04-24 | Disable Flyway in `test` profile | Tests use H2 + service-layer mocks; Testcontainers migration deferred |
| 2026-04-24 | Defer `CREATE INDEX CONCURRENTLY` until first prod deploy | Not needed in dev; adds transaction-handling complexity |

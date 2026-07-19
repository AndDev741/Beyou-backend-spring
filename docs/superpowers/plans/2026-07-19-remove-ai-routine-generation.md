# Remove AI Routine Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the standalone AI routine generation feature from all four repos — the agent chat (LLM fallback chain) replaces it.

**Architecture:** Pure removal, compile/test-driven: delete the feature surface, let the compiler/tests surface dangling references, prune them, verify with greps. One PR per repo, merged in CI-safe order.

**Tech Stack:** Java/Spring Boot (backend), React+RN monorepo (frontend), Playwright (e2e), markdown/OpenAPI (arch-design).

**Spec:** `docs/superpowers/specs/2026-07-19-remove-ai-routine-generation-design.md`

## Global Constraints

- **Merge order is a hard constraint:** e2e → frontend → backend → arch-design. The backend PR's E2E CI job runs the e2e repo's main suite.
- Maven command is `mvn`, NOT `./mvnw`. Run `mvn clean` before full suites (stale-class history in target/).
- Each repo: branch from freshly pulled main (`git branch --set-upstream-to` may be missing — verify pull actually moved HEAD).
- `spring-ai-starter-model-openai` STAYS in the backend pom (LLM chain uses the library).
- `AiIconCatalog` MOVES to `domain/aiAgent/` — it must not be deleted.
- Success grep in every repo before its PR: `grep -ri "AiRoutine\|RoutineDraft\|aiRoutine\|ai/routine" --exclude-dir={.git,node_modules,dist,target,.turbo}` → only git-history/changelog hits allowed.
- Commits/PRs: user already authorized PRs for this task ("Vai fundo"); still show a summary per repo before each `gh pr create`.

---

### Task 1: Beyou-e2e-tests — remove the AI routine spec

**Files:**
- Delete: `tests/ai-routine.spec.ts`
- Modify (prune AI-only symbols, keep shared ones): `support/apiClient.ts`, `support/testData.ts`, `fixtures/auth.ts`

**Steps:**
- [ ] `cd ~/andP/beyou/Beyou-e2e-tests && git checkout main && git pull --ff-only` (verify HEAD moved)
- [ ] `git checkout -b chore/remove-ai-routine-spec`
- [ ] `rm tests/ai-routine.spec.ts`
- [ ] `grep -rn "ai" support/ fixtures/ --include="*.ts" -i | grep -iv "await\|main\|detail\|email\|available"` — for each AI-routine-only helper (e.g. generate/confirm API calls, canned-draft test data), delete it; keep anything used by other specs (verify with grep per symbol before deleting)
- [ ] `npx tsc --noEmit 2>/dev/null || npx playwright test --list` — confirm the suite still parses (no missing imports)
- [ ] Success grep (global constraint) → clean
- [ ] Commit `chore: remove AI routine wizard spec (feature removed, agent chat replaces it)`, push, `gh pr create`

### Task 2: Beyou-Frontend — remove wizard (web), sheet (mobile), shared packages

**Files:**
- Delete: `apps/web/src/components/routines/ai/` (whole dir: `CreateRoutineWithAi.tsx`, `DescribeStep.tsx`, `CreateRoutineWithAi.test.tsx`, anything else inside)
- Modify: `apps/web/src/components/routines/dailyRoutine/CreateDailyRoutine.tsx`, `.../EditDailyRoutine.tsx` (remove the wizard entry point/button + import)
- Delete: `apps/mobile/src/ui/routines/AiRoutineSheet.tsx`, `apps/mobile/__tests__/AiRoutineSheet.test.tsx`
- Modify: `apps/mobile/src/ui/routines/RoutineBuilder.tsx` (remove sheet trigger + import)
- Delete: `packages/api/src/ai/generateRoutine.ts` (and `src/ai/` dir if empty)
- Modify: `packages/api/src/index.ts` (remove export)
- Delete: `packages/validation/src/forms/aiRoutineSchemas.ts` + `aiRoutineSchemas.test.ts`
- Modify: `packages/validation/src/index.ts` (remove export)
- Modify: `packages/i18n/src/en/translation.json` + `pt/translation.json` (remove keys used only by the deleted components — find them by grepping the deleted files' `t(...)` keys first)
- Modify: `packages/contracts/openapi.json` (remove `/ai/routine/*` paths + orphaned component schemas), then `npm run generate` inside `packages/contracts` to regen `src/schema.d.ts`
- Check: `apps/mobile/AGENTS.md` mentions AiRoutineSheet — update

**Steps:**
- [ ] `cd ~/andP/beyou/Beyou-Frontend && git checkout main && git pull --ff-only` (verify HEAD moved)
- [ ] `git checkout -b chore/remove-ai-routine-wizard`
- [ ] BEFORE deleting: `grep -o "t('[^']*')" apps/web/src/components/routines/ai/*.tsx apps/mobile/src/ui/routines/AiRoutineSheet.tsx | sort -u` — record the i18n keys to prune
- [ ] Delete/modify files per the list above; contracts: prune json then `npm run generate`; `npm run check` must pass
- [ ] Run the workspace test suites and builds (whatever the repo uses — `npx turbo run test build` or per-package scripts); fix any dangling import the compiler finds
- [ ] Success grep (global constraint) → clean (i18n: also grep the recorded keys — zero remaining usages)
- [ ] Commit `chore: remove AI routine wizard (web + mobile + shared packages)`, push, `gh pr create` (note in body: merge AFTER Beyou-e2e-tests PR, BEFORE backend PR)

### Task 3: Beyou-backend-spring — remove domain/ai + endpoints

**Files:**
- Move: `domain/ai/AiIconCatalog.java` → `domain/aiAgent/AiIconCatalog.java` (package line + imports in `aiAgent/tools/Tools.java`, `aiAgent/AiAgentService.java`, and `AiDraftValidator` ref dies with it)
- Move: `src/test/java/beyou/beyouapp/backend/unit/ai/AiIconCatalogTest.java` → `.../unit/aiAgent/AiIconCatalogTest.java` (adjust package/imports)
- Delete: rest of `src/main/java/beyou/beyouapp/backend/domain/ai/` (AiRoutineService, AiRoutineConfirmService, AiDraftValidator, AiUserContextBuilder, RoutineDraftGenerator, SpringAiRoutineDraftGenerator, CannedRoutineDraftGenerator, `dto/` × 12)
- Delete: `controllers/AiRoutineController.java`
- Delete: `src/test/.../controller/AiRoutineControllerTest.java`, `integration/ai/` (3 ITs), `unit/ai/` (AiDraftValidatorTest, AiRoutineServiceTest, AiUserContextBuilderTest, RoutineDraftDtoSerializationTest)
- Delete: `src/main/resources/prompts/routine-generation.st`
- Delete: `exceptions/ai/AiGenerationException.java`; prune its `GlobalExceptionHandler` mapping and any `ErrorKey` enum entries whose ONLY consumers died (verify each with grep before removing)
- Modify: `security/ratelimit/RateLimitFilter.java` — remove `AI_GENERATE_PATH` constant + its bucket tier/branch
- Modify: `application.yaml` — remove `ai.routine.enabled` (keep `ai.llm-chain`!) and the `spring.ai.openai` block; try adding `spring.ai.model.chat: deepseek` to silence the OpenAI chat auto-config; if the context fails to boot in tests, revert to keeping the `spring.ai.openai` block with `dev-noop` + comment
- Modify: `application-e2e.yml` — remove `spring.ai.openai` override
- Modify: `envExample` — remove `AI_API_KEY`, `AI_ROUTINE_MODEL`, `AI_ROUTINE_ENABLED` (keep the AGENT/chain vars)
- Modify: `CLAUDE.md` (backend) — remove/adjust AI routine sections; `~/andP/beyou/CLAUDE.md` (root) — same (feature description, e2e table row `ai-routine.spec.ts`, env mentions)

**Steps:**
- [ ] Preconditions: Task 1 AND Task 2 PRs MERGED (backend E2E CI runs e2e main)
- [ ] `git checkout main && git pull --ff-only && git checkout -b chore/remove-ai-routine-generation`
- [ ] Move AiIconCatalog + test first; `mvn compile` → fix imports in Tools/AiAgentService
- [ ] Delete files per list; `mvn compile` after each batch — the compiler is the checklist
- [ ] ErrorKey prune: for each candidate entry, `grep -rn "ERRORKEY_NAME" src/` → remove only if zero consumers remain
- [ ] `mvn clean test` → full suite green
- [ ] Success grep (global constraint) → clean
- [ ] CLAUDE.md updates (both files)
- [ ] Commit (suggested: one commit `refactor: remove AI routine generation — agent chat replaces it`; move of AiIconCatalog included), push, `gh pr create`, wait CI green (Monitor)

### Task 4: Beyou-arch-design — prune docs

**Files (verify at execution):**
- Check `api/routine/openapi.yaml` for `/ai/routine` paths — remove if present (there is NO `api/ai/` dir)
- Modify: `architecture/domain-model/en.md`, `architecture/overview/en.md` + their `pt.md` twins — remove AI-routine-generation mentions
- Grep `blog/` and `projects/` for feature mentions

**Steps:**
- [ ] `cd ~/andP/beyou/Beyou-arch-design && git checkout main && git pull --ff-only && git checkout -b chore/remove-ai-routine-docs`
- [ ] Prune per list; success grep → clean
- [ ] Commit `docs: remove AI routine generation (feature removed)`, push, `gh pr create`

### Final verification

- [ ] All 4 PRs green; merge in order: e2e → frontend → backend → arch-design
- [ ] After backend merge: boot dev stack, ask the agent chat to create a routine (manual smoke)

## Self-Review

- Spec coverage: every spec bullet maps to a task ✔. The `spring.ai.model.chat` decision procedure is embedded in Task 3 ✔.
- Placeholders: Task 4 file list marked "verify at execution" deliberately — arch-design content is discovered by grep, not known a priori; the greps ARE the instruction ✔.
- Consistency: AiIconCatalog move target (`domain/aiAgent/`) matches consumers' package ✔. Merge order stated identically in constraints, Task 2, Task 3 ✔.

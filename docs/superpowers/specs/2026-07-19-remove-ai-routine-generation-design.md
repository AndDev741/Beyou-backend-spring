# Remove AI Routine Generation — Design

**Date:** 2026-07-19
**Status:** Approved (pending user review of this doc)
**Reason:** The agent chat (with the LLM fallback chain, PR #68) covers routine creation
interactively — the standalone draft/confirm wizard is redundant. Deletion over
maintenance.

## Scope

Full removal across all four repos. No feature flag, no deprecation window (single-user
app in development).

## Merge order (CI-driven — this is a hard constraint)

The backend PR's E2E job runs the `Beyou-e2e-tests` main suite; the frontend e2e spec
drives the web wizard. Removing in the wrong order leaves a permanently red CI.

1. **Beyou-e2e-tests** — remove `tests/ai-routine.spec.ts`; prune AI-only helpers in
   `support/apiClient.ts`, `support/testData.ts`, `fixtures/auth.ts` (keep anything
   shared with other specs).
2. **Beyou-Frontend** (monorepo) — remove:
   - web: `apps/web/src/components/routines/ai/` (`CreateRoutineWithAi`, `DescribeStep`,
     tests) and its entry point in the routines page
   - mobile: `apps/mobile/src/ui/routines/AiRoutineSheet.tsx`, its test, and the
     reference inside `RoutineBuilder.tsx`
   - `packages/api/src/ai/generateRoutine.ts` + its export in `packages/api/src/index.ts`
   - `packages/validation` aiRoutineSchemas + test + index export
   - i18n keys (en + pt) used only by the wizard/sheet
   - `packages/contracts`: drop the `/ai/routine/*` endpoints following the repo's own
     regeneration mechanism (regen from arch-design if scripted; hand-prune otherwise)
3. **Beyou-backend-spring** — see below.
4. **Beyou-arch-design** — remove the AI routine OpenAPI spec (`api/ai/…`) and any
   architecture/blog doc pages dedicated to the feature. No CI coupling; content-level.

## Backend removal detail

**Moves (agent now owns it):**
- `domain/ai/AiIconCatalog.java` → `domain/aiAgent/AiIconCatalog.java` (consumers:
  `aiAgent/tools/Tools`, `AiAgentService`)
- `unit/ai/AiIconCatalogTest.java` → the matching aiAgent test package

**Deletes:**
- Rest of `domain/ai/`: `AiRoutineService`, `AiRoutineConfirmService`, `AiDraftValidator`,
  `AiUserContextBuilder`, `RoutineDraftGenerator`, `SpringAiRoutineDraftGenerator`,
  `CannedRoutineDraftGenerator`, `dto/` (12 DTOs)
- `controllers/AiRoutineController.java`
- Tests: `AiRoutineControllerTest`, `integration/ai/` (3 ITs), `unit/ai/` (the 4
  remaining after the catalog test moves)
- `resources/prompts/routine-generation.st`
- `RateLimitFilter`: the `/ai/routine/generate` bucket tier (`AI_GENERATE_PATH`)
- Feature-only exceptions/ErrorKeys (e.g. `exceptions/ai/AiGenerationException`) and
  their `GlobalExceptionHandler` mappings — verify no other consumer before each delete

**Config:**
- Remove `ai.routine.enabled` (application.yaml) and `AI_ROUTINE_ENABLED`,
  `AI_API_KEY`, `AI_ROUTINE_MODEL` (envExample)
- Remove the `spring.ai.openai` block (main + `application-e2e.yml`). The auto-configured
  `OpenAiChatModel` bean loses its only consumer; attempt to disable the OpenAI chat
  auto-config with `spring.ai.model.chat: deepseek`. If that property fights the context
  (DeepSeek must stay auto-configured), keep the `spring.ai.openai` block with a
  `dev-noop` key and a comment instead — decided at implementation time by booting the
  test context.
- **`spring-ai-starter-model-openai` STAYS in the pom** — the LLM fallback chain builds
  manual `OpenAiChatModel`s from this library (`OpenAiSetup`).

**Docs:** update the backend `CLAUDE.md` and the root `~/andP/beyou/CLAUDE.md` sections
that describe AI routine generation (feature description, e2e coverage table row,
env vars).

## Success criteria

- `grep -ri "ai/routine\|AiRoutine\|RoutineDraft\|aiRoutine"` returns nothing in all
  four repos (excluding git history / changelogs)
- Full test suites green in backend (mvn), frontend (vitest, web + mobile + packages),
  e2e (Playwright)
- Backend PR CI fully green (Build & Test, E2E, CodeQL, migrations guard — no entity
  changes expected, so no migration)
- Agent chat still creates routines end-to-end (already covered by agent tests; manual
  smoke after backend merge)

## Out of scope

- Removing the paid OpenAI account/key from any deployed environment (user-managed)
- Any change to the agent chat or the LLM fallback chain
- The old stubbed `generateCategoryByAi` frontend component (separate feature, already
  a dead stub — untouched here)

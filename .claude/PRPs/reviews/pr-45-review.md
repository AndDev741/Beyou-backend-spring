# PR Review: #45 — feat(auth): POST /auth/google/mobile for native Google sign-in

**Reviewed**: 2026-06-29
**Author**: AndDev741
**Branch**: feat/google-mobile-auth → main
**Decision**: APPROVE with comments

## Summary
Clean, well-scoped backend endpoint for native Google sign-in. It mirrors the established web Google flow and the mobile `/auth/login` contract (X-Access-Token header + `refreshToken` in body, no cookie), puts verification behind a testable seam, and ships unit + controller tests that pass. No CRITICAL/HIGH issues. One MEDIUM worth fixing before or just after merge: the new unauthenticated endpoint is not covered by the rate-limit filter.

## Findings

### CRITICAL
None.

### HIGH
None.

### MEDIUM

**M1 — `/auth/google/mobile` is not rate-limited** (`security/ratelimit/RateLimitFilter.java:27,68`)
The new endpoint is an unauthenticated POST. Tracing it through the filter: not in `AUTH_PATHS`; not the AI path; not `/docs`; it matches the `WRITE_METHODS` branch, but there `getUserIdFromRequest` returns `null` (no JWT yet) → `filterChain.doFilter(...); return;` → **no bucket applied**. So the endpoint is unthrottled. The project's own security checklist requires "rate limiting on all endpoints," and `/auth/login`, `/auth/register`, `/auth/forgot-password` are all IP-throttled.
Practical exploitability is limited (invalid tokens throw in `verify()` before any DB write; only local signature checks run), and the web `/auth/google` is likewise unthrottled — but that one requires a Google authorization `code` bound to redirect_uri + client secret, a higher bar than accepting an arbitrary `idToken` string.
**Fix**: add `"/auth/google/mobile"` to `AUTH_PATHS` so it shares the IP-based auth bucket.

**M2 — Verifier swallows the failure cause with no server-side log** (`user/GoogleIdTokenVerifierServiceImpl.java:38-45`)
`catch (Exception e)` rethrows a generic `BusinessException` and discards `e`. A misconfigured audience, clock skew, or a Google key-fetch outage all collapse to "Invalid Google ID token" with no diagnostic trail (the AOP layer logs `BusinessException` at WARN without a stack trace). This will make a production misconfig hard to diagnose.
**Fix**: log the underlying cause server-side at WARN/DEBUG (message only — never the token).

### LOW

**L1 — Over-broad catch** (`GoogleIdTokenVerifierServiceImpl.java:38`)
`verifier.verify(...)` declares `GeneralSecurityException` + `IOException`. Catching `Exception` also masks runtime/programming errors (NPE, etc.) as "invalid token." Narrow the catch or log so genuine faults aren't hidden. (Pairs with M2.)

**L2 — Real verifier impl is untested**
Both the unit test and controller test mock `GoogleIdTokenVerifierService`, so `GoogleIdTokenVerifierServiceImpl` (audience parsing, the `email_verified` guard, the null-payload path) has zero coverage. Acceptable for a thin adapter over Google's library, but the `email_verified` guard is real logic — consider one test against a hand-built/fake token.

**L3 — Dead `X-Client: mobile` header in test** (`AuthenticationControllerTest` `mobileGoogleSignInReturnsProfileAndRefreshTokenForValidIdToken`)
The endpoint hardcodes `mobile=true`, so unlike `/auth/login` the `X-Client` header has no effect here. Harmless but misleading — drop it.

**L4 — Empty `google.mobile.audiences` fails closed** (`GoogleIdTokenVerifierServiceImpl.java:23-29`)
If the property resolves to empty/whitespace, the audience list is empty and `GoogleIdTokenVerifier` rejects every token (fail-closed — safe, not a hole). Prod defaults to `GOOGLE_CLIENT_ID`, so this only bites on misconfig. Optional: assert non-empty at startup for fail-fast instead of silent 400s.

## Validation Results

| Check | Result |
|---|---|
| Type check / compile | Pass (mvn compiled to run tests) |
| Lint | Skipped (no linter configured) |
| Tests | Pass — `UserServiceGoogleMobileAuthUnitTest` 3/3, `AuthenticationControllerTest` 26/26 |
| Build | Skipped full `package`; targeted test build clean |

## Files Reviewed
- `envExample` — Modified (documents `GOOGLE_MOBILE_AUDIENCES`)
- `pom.xml` — Modified (`google-api-client:2.7.0`)
- `controllers/AuthenticationController.java` — Modified (endpoint)
- `security/SecurityConfig.java` — Modified (allowlist)
- `user/GoogleIdTokenVerifierService.java` — Added (interface seam)
- `user/GoogleIdTokenVerifierServiceImpl.java` — Added (real verifier)
- `user/UserServiceGoogleOAuth.java` — Modified (`googleMobileAuth`)
- `user/dto/GoogleMobileLoginDTO.java` — Added (`@NotBlank idToken`)
- `resources/application.yaml` / `application-test.yml` / `application-e2e.yml` — Modified (`google.mobile.audiences`)
- `test/.../controller/AuthenticationControllerTest.java` — Modified (3 e2e tests)
- `test/.../unit/user/UserServiceGoogleMobileAuthUnitTest.java` — Added (3 unit tests)

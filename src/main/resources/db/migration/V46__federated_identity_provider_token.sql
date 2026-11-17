-- Sign in with Apple: keep the one credential that lets us revoke the link later.
--
-- App Store guideline 5.1.1(v) asks an app that offers account deletion to revoke the
-- user's Sign in with Apple tokens when the account goes. Apple's revoke endpoint takes a
-- refresh token (or an access token, which lives an hour), and the only way to get one is
-- to exchange the authorization code the device receives at sign-in, with a client secret
-- signed by our Sign in with Apple key. So the backend exchanges that code when the key is
-- configured, and stores the refresh token here until the account is deleted.
--
-- provider_refresh_token: the refresh token from that exchange. Not a way into beyou (our
-- sessions are our own JWTs) and useless at Apple without the .p8 key that signs the client
-- secret, which lives only in the deployment's environment. Never exported, never logged.
-- NULL when the key was not configured at sign-in, or for any provider but Apple.
--
-- provider_client_id: the aud the token was issued to (the iOS bundle id, or a Services ID
-- on the web). Apple only revokes a token when the client_id matches the one it was issued
-- for, and the backend accepts more than one audience.
--
-- Both columns go with the row, and the row goes with the account (ON DELETE CASCADE, V29).
-- Revocation reads them before the delete and calls Apple after the commit.
--
-- Nullable additions with no default: a catalogue-only change, no rewrite, no backfill.
-- SET LOCAL, not SET. See V13/V14/V20. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE federated_identities ADD COLUMN IF NOT EXISTS provider_refresh_token text;
ALTER TABLE federated_identities ADD COLUMN IF NOT EXISTS provider_client_id text;

-- Whether the person agreed to Beyou sending what they write to its AI provider.
--
-- App Store guideline 5.1.2(i) asks an app to say which third-party AI receives a person's
-- data, and to get explicit permission before it does. The phone app now asks once, before
-- the first AI feature runs, and Configuration can change the answer later. The answer is
-- kept here rather than on the device so that it holds on every device and in the daily
-- briefing, which reaches the provider from the server without the person opening anything.
--
-- ai_consent: UNASKED for every existing row (nobody was asked), GRANTED or DECLINED once
-- the person answers. Only DECLINED switches anything off; see AiConsent.
-- ai_consent_at: when the current answer was given. NULL while UNASKED.
--
-- SET LOCAL, not SET. See V13/V14/V20. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- varchar(16) like timezone_source (V20). A non-volatile default is stored in the catalog,
-- so this is no table rewrite.
-- squawk-ignore prefer-text-field
ALTER TABLE users ADD COLUMN IF NOT EXISTS ai_consent varchar(16) NOT NULL DEFAULT 'UNASKED';
ALTER TABLE users ADD COLUMN IF NOT EXISTS ai_consent_at timestamptz;

-- The constraint is new, so this drops nothing; it lets a rerun after a failure part way through
-- add it again instead of stopping on a duplicate.
-- squawk-ignore ban-drop-constraint
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_ai_consent_check;

-- Mirrored by AiConsent. A value added there without adding it here fails every write of it.
-- NOT VALID: new writes are checked at once, and the scan of the existing rows (which can only
-- hold the default) runs in V49 under a lighter lock, so this never holds users for a scan.
ALTER TABLE users ADD CONSTRAINT users_ai_consent_check CHECK (ai_consent IN ('UNASKED', 'GRANTED', 'DECLINED')) NOT VALID;

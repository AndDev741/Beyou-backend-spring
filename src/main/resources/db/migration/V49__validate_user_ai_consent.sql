-- The second half of V48: check the rows that existed before the constraint did.
--
-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE, which lets reads and writes on users go on
-- while it scans. Its own migration so V48's ACCESS EXCLUSIVE lock is released first.
--
-- SET LOCAL, not SET. See V13/V14/V20.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE users VALIDATE CONSTRAINT users_ai_consent_check;

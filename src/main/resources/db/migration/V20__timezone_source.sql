-- Record where users.timezone came from, so it can be corrected without overwriting
-- anyone who meant it.
--
-- The column has always been NOT NULL with the entity supplying 'UTC' (User.java:133,
-- reaffirmed by the @PrePersist), and none of the four signup paths ever set it: web
-- register, mobile register, Google web, Google mobile all create with UTC. Detection
-- exists only behind an opt-in click in Configuration -> Routine, so in practice every
-- account runs on the UTC calendar wherever the person actually is.
--
-- UserDateResolver is the single authority on "what day is it" for eleven call sites,
-- so that one wrong string decides check days, streaks, the XP ledger, the snapshot
-- hour and the day-close hour. In Europe/Lisbon the cost is one hour during WEST and
-- nothing during WET; at a larger offset it is a day boundary hours out of place and a
-- MISSED that DayCloseService can never take back, because that pass is insert-only.
--
-- The blocker to fixing it was that 'UTC' is simultaneously the default and a valid
-- answer, so a blind backfill would overwrite a deliberate UTC pick. This column is
-- what separates the two. See TimezoneSource for the policy each value carries.
--
-- Squawk ignores are per-line (attach to the next line) — same note as V11.
--
-- SET LOCAL, not SET — see V13/V14. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- varchar(16) rather than text, matching every other @Enumerated(STRING) column in
-- this schema (entity_check_day.owner_type, entity_xp_day.owner_type). Safe to add on
-- Postgres 11+: a non-volatile default is stored in the catalog and no table rewrite
-- happens, so this takes a brief ACCESS EXCLUSIVE lock and returns. The lock_timeout
-- above is the backstop if it ever queues behind live traffic.
-- squawk-ignore prefer-text-field
ALTER TABLE users ADD COLUMN IF NOT EXISTS timezone_source varchar(16) NOT NULL DEFAULT 'DEFAULT';

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_timezone_source_check;

-- Mirrored by TimezoneSource. Adding a value there without adding it here makes every
-- write of the new kind fail, which is the same contract V19 documents for owner_type.
-- squawk-ignore constraint-missing-not-valid
ALTER TABLE users ADD CONSTRAINT users_timezone_source_check CHECK (timezone_source IN ('DEFAULT', 'DETECTED', 'EXPLICIT'));

-- UserService.editUser is the ONLY writer of users.timezone, reachable from the
-- Configuration screen and from the agent's updateUserConfiguration tool. So any row
-- holding a non-UTC zone got there because a person chose it, and those must never be
-- auto-corrected.
--
-- Rows still on 'UTC' stay DEFAULT. They are ambiguous by construction and the client
-- reconcile is allowed to adopt over them exactly once; someone who really wanted UTC
-- picks it again and is EXPLICIT from then on.
--
-- Beyou has no production users at the time of writing, so this touches only dev and
-- e2e rows. The rule is written to be correct once it does matter rather than to be
-- adequate for four rows.
UPDATE users SET timezone_source = 'EXPLICIT' WHERE timezone <> 'UTC';

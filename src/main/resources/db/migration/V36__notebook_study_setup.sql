-- The study room's setup, kept per page: what the person wants out of studying it, and which
-- notes the AI reads.
--
-- Before the first question the study room asks for a goal ("prepare the C1 oral exam") and a
-- scope for the notes. The goal goes into every answer's context so the answers aim at it. The
-- scope decides whose notes the passages are drawn from:
--   PAGE    this page, and the pages above it for context (what the room read before V36)
--   SUBTREE this page and every page under it
--   TOPIC   every page of the topic
-- Sources keep their own rule (a page reads its own and its ancestors', each switchable), so the
-- scope here is about notes only. `study_setup_at` is when the setup was last saved; null means
-- the room has never been set up and opens on the setup screen.

-- SET LOCAL, not SET: see V13/V14/V19/V26/V27/V30/V31/V32/V33.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- squawk-ignore prefer-text-field
ALTER TABLE notebook_pages ADD COLUMN IF NOT EXISTS study_goal varchar(300);
-- Mirrors StudyScope. Adding a value in Java without adding it to the CHECK below makes every
-- save of the new scope fail. A constant default, so existing rows are not rewritten.
-- squawk-ignore prefer-text-field
ALTER TABLE notebook_pages ADD COLUMN IF NOT EXISTS study_scope varchar(16) NOT NULL DEFAULT 'PAGE';
ALTER TABLE notebook_pages ADD COLUMN IF NOT EXISTS study_setup_at timestamptz;

ALTER TABLE notebook_pages DROP CONSTRAINT IF EXISTS notebook_pages_study_scope_check;
ALTER TABLE notebook_pages ADD CONSTRAINT notebook_pages_study_scope_check
    CHECK (study_scope IN ('PAGE', 'SUBTREE', 'TOPIC')) NOT VALID;
ALTER TABLE notebook_pages VALIDATE CONSTRAINT notebook_pages_study_scope_check;

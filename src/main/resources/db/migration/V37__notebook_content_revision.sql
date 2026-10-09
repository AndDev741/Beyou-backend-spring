-- A number per page that goes up every time its document is written.
--
-- The page's document is one JSON column, saved whole by the editor's autosave. Until now the
-- last save won: a phone and a computer editing the same page, or the assistant appending notes
-- while the page was open, meant one write silently removed the other's. Every writer now
-- writes with a compare-and-set on this number (UPDATE ... WHERE content_revision = :read), and
-- an editor that saves from an older revision gets NOTEBOOK_CONTENT_CONFLICT and merges.
--
-- Only the document is versioned. Status, title, icon and the rest keep their targeted updates
-- (@DynamicUpdate), because nothing about them is ever merged.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE notebook_pages ADD COLUMN IF NOT EXISTS content_revision bigint NOT NULL DEFAULT 0;

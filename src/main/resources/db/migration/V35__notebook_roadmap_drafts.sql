-- Roadmap drafts for "New topic with AI", kept until the person creates the topic or deletes
-- the draft.
--
-- The draft used to live only in the dialog. A model call takes up to a minute and a half, and
-- a click outside the dialog threw the whole thing away: the request, the drafted nodes and the
-- ticks the person had set. Now "Draft" writes this row first, the model fills it in the
-- background, and the notebook home lists it, so the person can leave and come back to it.
--
-- `request` is what the person asked for (RoadmapDraftRequestDTO as JSON), `result` the drafted
-- nodes (RoadmapDraftDTO as JSON) and `choices` which nodes they kept and which they chose to
-- link, one entry per node in `result`. JSON in `text` columns like the rest of the notebook.
-- `started_at` is when the current model call began, so a dialog opened halfway through shows
-- how long it has really been running.
--
-- One model call per draft at a time: a draft that is DRAFTING refuses another until it ends,
-- and the background job only writes to a row that is still DRAFTING, so a draft deleted
-- mid-call stays deleted.

-- SET LOCAL, not SET: see V13/V14/V19/V26/V27/V30/V31/V32/V33.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS notebook_roadmap_drafts (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- squawk-ignore prefer-text-field
    title varchar(255) NOT NULL,
    -- Mirrors RoadmapDraftStatus. Adding a value in Java without adding it here makes every
    -- write of the new status fail.
    -- squawk-ignore prefer-text-field
    status varchar(16) NOT NULL,
    request text NOT NULL,
    result text,
    choices text,
    -- squawk-ignore prefer-text-field
    error_key varchar(64),
    started_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT notebook_roadmap_drafts_pkey PRIMARY KEY (id),
    CONSTRAINT notebook_roadmap_drafts_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT notebook_roadmap_drafts_status_check CHECK (status IN ('DRAFTING', 'READY', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_notebook_roadmap_drafts_user ON notebook_roadmap_drafts (user_id, updated_at);

-- The study notebook: topics, pages, a roadmap board per page, flashcards, sources and the
-- study room's chat and outputs.
--
-- Everything is a page. A topic is a root page (kind TOPIC, no parent), and every other page
-- has exactly one home: `parent_id` for the tree, `topic_id` for "which topic does this belong
-- to" without walking the tree on every read. A board belongs to the page that shows it, so
-- its rows are keyed by `board_page_id`, and a PAGE node on it points at the page the node
-- opens. Usually that page is a child of the board's page; a node may also point at a page
-- that lives in another topic ("link it" in the AI draft), which is why the node has its own
-- `page_id` instead of reusing the tree. NotebookBoardService refuses a link that would make a
-- page reachable from itself.
--
-- Content is BlockNote JSON in a `text` column, like every other JSON this schema stores
-- (agent_message.content, daily_briefing.narrative_json): no JSONB anywhere, and nothing
-- queries inside the document. `content_text` is the plain text extracted ON THE SERVER from
-- that JSON (BlockText.extract), because it is what the AI reads and what search matches, and
-- a client-supplied copy could say anything.
--
-- Status is stored, not computed per read, because XP is paid on the transition to DONE and a
-- transition needs a before. NotebookProgressService is the only writer and recomputes
-- upward through every board that shows the page.
--
-- Source chunks keep the text and an optional embedding as `real[]`. No pgvector: it would
-- need the database image swapped in prod, in three compose files, in every CI service and in
-- Testcontainers, and a backend merged before that swap would fail here, at Flyway time,
-- and take the API down with it. One person's sources are small enough for cosine in the
-- JVM, and the generated tsvector below is the fallback when the embedding provider is
-- missing or down. Moving to pgvector later is a column type change and one query.
--
-- PDFs themselves are not stored. They are parsed in memory and only their text survives,
-- per page, in the chunks. There is no file on disk to clean up when an account goes.
--
-- Every table cascades from users: account deletion takes the whole notebook, which the
-- account-deletion tests assert for every user-owned table.
--
-- SET LOCAL, not SET: see V13/V14/V19/V26/V27/V30/V31/V32/V33. Flyway has no datasource of its
-- own, so a session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS notebook_pages (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- Null only for topics. CASCADE: deleting a page deletes its subtree, and the client says
    -- so before it asks.
    parent_id uuid,
    -- The root topic, denormalised so a topic's whole tree is one indexed read. Null for the
    -- topic row itself.
    topic_id uuid,
    -- Mirrors NotebookPageKind. Adding a value in Java without adding it here makes every
    -- write of the new kind fail at insert time.
    -- squawk-ignore prefer-text-field
    kind varchar(16) NOT NULL,
    -- squawk-ignore prefer-text-field
    title varchar(255) NOT NULL,
    -- squawk-ignore prefer-text-field
    icon varchar(64),
    -- squawk-ignore prefer-text-field
    description varchar(512),
    content text,
    content_text text,
    -- Mirrors NotebookStatus.
    -- squawk-ignore prefer-text-field
    status varchar(16) DEFAULT 'TO_STUDY' NOT NULL,
    -- True when the person set the status by hand on a page whose board has nodes. False lets
    -- the nodes decide.
    status_manual boolean DEFAULT false NOT NULL,
    -- Order among siblings in the tree. Bounded by the number of pages one person writes.
    -- squawk-ignore prefer-bigint-over-int
    position integer DEFAULT 0 NOT NULL,
    goal_id uuid,
    category_id uuid,
    habit_id uuid,
    -- When the 15 XP for finishing this page was paid. Set once and never cleared, so marking
    -- a page done, undone and done again pays once.
    done_xp_at timestamptz,
    last_opened_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT notebook_pages_pkey PRIMARY KEY (id),
    CONSTRAINT notebook_pages_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT notebook_pages_parent_fkey FOREIGN KEY (parent_id)
        REFERENCES notebook_pages (id) ON DELETE CASCADE,
    CONSTRAINT notebook_pages_topic_fkey FOREIGN KEY (topic_id)
        REFERENCES notebook_pages (id) ON DELETE CASCADE,
    -- The links are decoration on the topic. Deleting the goal, category or habit must not
    -- delete a notebook.
    CONSTRAINT notebook_pages_goal_fkey FOREIGN KEY (goal_id)
        REFERENCES goals (id) ON DELETE SET NULL,
    CONSTRAINT notebook_pages_category_fkey FOREIGN KEY (category_id)
        REFERENCES categories (id) ON DELETE SET NULL,
    CONSTRAINT notebook_pages_habit_fkey FOREIGN KEY (habit_id)
        REFERENCES habits (id) ON DELETE SET NULL,
    CONSTRAINT notebook_pages_kind_check CHECK (kind IN ('TOPIC', 'PAGE')),
    CONSTRAINT notebook_pages_status_check CHECK (status IN ('TO_STUDY', 'STUDYING', 'DONE')),
    -- A topic is a root and a page is not. The service builds rows this way; the database
    -- refuses anything else.
    CONSTRAINT notebook_pages_shape_check CHECK (
        (kind = 'TOPIC' AND parent_id IS NULL AND topic_id IS NULL)
        OR (kind = 'PAGE' AND parent_id IS NOT NULL AND topic_id IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_notebook_pages_user_kind ON notebook_pages (user_id, kind);
CREATE INDEX IF NOT EXISTS idx_notebook_pages_topic ON notebook_pages (topic_id);
CREATE INDEX IF NOT EXISTS idx_notebook_pages_parent ON notebook_pages (parent_id);

CREATE TABLE IF NOT EXISTS notebook_board_nodes (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- The page that shows the board.
    board_page_id uuid NOT NULL,
    -- The page this node opens. Null for a SECTION, which is a labelled background band.
    page_id uuid,
    -- Mirrors NotebookNodeKind.
    -- squawk-ignore prefer-text-field
    kind varchar(16) NOT NULL,
    -- squawk-ignore prefer-text-field
    label varchar(255),
    x double precision NOT NULL,
    y double precision NOT NULL,
    width double precision,
    height double precision,
    created_at timestamptz NOT NULL,
    CONSTRAINT notebook_board_nodes_pkey PRIMARY KEY (id),
    CONSTRAINT notebook_board_nodes_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT notebook_board_nodes_board_fkey FOREIGN KEY (board_page_id)
        REFERENCES notebook_pages (id) ON DELETE CASCADE,
    CONSTRAINT notebook_board_nodes_page_fkey FOREIGN KEY (page_id)
        REFERENCES notebook_pages (id) ON DELETE CASCADE,
    CONSTRAINT notebook_board_nodes_kind_check CHECK (kind IN ('PAGE', 'SECTION')),
    CONSTRAINT notebook_board_nodes_shape_check CHECK (
        (kind = 'PAGE' AND page_id IS NOT NULL) OR (kind = 'SECTION' AND page_id IS NULL)),
    -- A page appears on a given board once. Sections have no page, and NULLs never collide.
    CONSTRAINT notebook_board_nodes_page_once UNIQUE (board_page_id, page_id)
);

-- "Which boards show this page" is the question every status change asks on its way up.
CREATE INDEX IF NOT EXISTS idx_notebook_board_nodes_page ON notebook_board_nodes (page_id);

CREATE TABLE IF NOT EXISTS notebook_board_edges (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    board_page_id uuid NOT NULL,
    source_node_id uuid NOT NULL,
    target_node_id uuid NOT NULL,
    CONSTRAINT notebook_board_edges_pkey PRIMARY KEY (id),
    CONSTRAINT notebook_board_edges_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT notebook_board_edges_board_fkey FOREIGN KEY (board_page_id)
        REFERENCES notebook_pages (id) ON DELETE CASCADE,
    CONSTRAINT notebook_board_edges_source_fkey FOREIGN KEY (source_node_id)
        REFERENCES notebook_board_nodes (id) ON DELETE CASCADE,
    CONSTRAINT notebook_board_edges_target_fkey FOREIGN KEY (target_node_id)
        REFERENCES notebook_board_nodes (id) ON DELETE CASCADE,
    CONSTRAINT notebook_board_edges_once UNIQUE (source_node_id, target_node_id),
    CONSTRAINT notebook_board_edges_not_self CHECK (source_node_id <> target_node_id)
);

CREATE INDEX IF NOT EXISTS idx_notebook_board_edges_board ON notebook_board_edges (board_page_id);

CREATE TABLE IF NOT EXISTS notebook_cards (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    page_id uuid NOT NULL,
    front text NOT NULL,
    back text NOT NULL,
    -- Where the card came from, for the review screen's footer ("From your page, BST deletion").
    -- squawk-ignore prefer-text-field
    source_label varchar(255),
    -- The owner's local day the card is next due, resolved through UserDateResolver. A day
    -- and not an instant: spaced repetition works in days, and "due tomorrow" must mean the
    -- reader's tomorrow.
    due_on date NOT NULL,
    -- Days until the next review after the last one, capped at 365 by SpacedRepetition.
    -- squawk-ignore prefer-bigint-over-int
    interval_days integer DEFAULT 0 NOT NULL,
    ease double precision DEFAULT 2.5 NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    reps integer DEFAULT 0 NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    lapses integer DEFAULT 0 NOT NULL,
    last_reviewed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT notebook_cards_pkey PRIMARY KEY (id),
    CONSTRAINT notebook_cards_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT notebook_cards_page_fkey FOREIGN KEY (page_id)
        REFERENCES notebook_pages (id) ON DELETE CASCADE
);

-- The review queue: this user's cards due on or before a day.
CREATE INDEX IF NOT EXISTS idx_notebook_cards_user_due ON notebook_cards (user_id, due_on);
CREATE INDEX IF NOT EXISTS idx_notebook_cards_page ON notebook_cards (page_id);

CREATE TABLE IF NOT EXISTS notebook_card_reviews (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    card_id uuid NOT NULL,
    -- Mirrors CardRating.
    -- squawk-ignore prefer-text-field
    rating varchar(8) NOT NULL,
    -- The owner's local day of the review. The review streak counts distinct days here.
    review_date date NOT NULL,
    reviewed_at timestamptz NOT NULL,
    -- Paid by POST /notebook/reviews/finish, at most 30 a day. A review whose XP was never
    -- collected on its own day stays unpaid; there is no back pay.
    xp_paid boolean DEFAULT false NOT NULL,
    CONSTRAINT notebook_card_reviews_pkey PRIMARY KEY (id),
    CONSTRAINT notebook_card_reviews_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT notebook_card_reviews_card_fkey FOREIGN KEY (card_id)
        REFERENCES notebook_cards (id) ON DELETE CASCADE,
    CONSTRAINT notebook_card_reviews_rating_check CHECK (rating IN ('AGAIN', 'HARD', 'GOOD', 'EASY'))
);

CREATE INDEX IF NOT EXISTS idx_notebook_card_reviews_user_date ON notebook_card_reviews (user_id, review_date);

CREATE TABLE IF NOT EXISTS notebook_sources (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- The page the source was added to. Pages below it see it too (scope inheritance).
    page_id uuid NOT NULL,
    -- Mirrors NotebookSourceKind.
    -- squawk-ignore prefer-text-field
    kind varchar(16) NOT NULL,
    -- squawk-ignore prefer-text-field
    title varchar(255) NOT NULL,
    -- squawk-ignore prefer-text-field
    url varchar(2048),
    -- Mirrors NotebookSourceStatus.
    -- squawk-ignore prefer-text-field
    status varchar(16) NOT NULL,
    -- 0..100, for the "Reading 62%" bar.
    -- squawk-ignore prefer-bigint-over-int
    progress integer DEFAULT 0 NOT NULL,
    -- The ErrorKey name when status is FAILED, so the client can translate why.
    -- squawk-ignore prefer-text-field
    error_key varchar(64),
    enabled boolean DEFAULT true NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    page_count integer,
    -- squawk-ignore prefer-bigint-over-int
    char_count integer,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT notebook_sources_pkey PRIMARY KEY (id),
    CONSTRAINT notebook_sources_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT notebook_sources_page_fkey FOREIGN KEY (page_id)
        REFERENCES notebook_pages (id) ON DELETE CASCADE,
    CONSTRAINT notebook_sources_kind_check CHECK (kind IN ('PDF', 'LINK', 'TEXT')),
    CONSTRAINT notebook_sources_status_check CHECK (status IN ('PENDING', 'READING', 'READY', 'FAILED')),
    CONSTRAINT notebook_sources_progress_check CHECK (progress >= 0 AND progress <= 100)
);

CREATE INDEX IF NOT EXISTS idx_notebook_sources_page ON notebook_sources (page_id);

CREATE TABLE IF NOT EXISTS notebook_source_chunks (
    id uuid NOT NULL,
    source_id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    ordinal integer NOT NULL,
    -- The PDF page the text came from, for "Open at page 296". Null for links and pasted text.
    -- squawk-ignore prefer-bigint-over-int
    page_number integer,
    content text NOT NULL,
    -- 'simple', not 'english': the notebook is bilingual, and a stemmer for one language
    -- mangles the other. Ranking on plain tokens is good enough for a fallback.
    search tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
    embedding real[],
    -- Which model made the embedding. Vectors from two models live in different spaces, so
    -- the retriever only compares a question with chunks embedded by the same model.
    -- squawk-ignore prefer-text-field
    embedding_model varchar(64),
    CONSTRAINT notebook_source_chunks_pkey PRIMARY KEY (id),
    CONSTRAINT notebook_source_chunks_source_fkey FOREIGN KEY (source_id)
        REFERENCES notebook_sources (id) ON DELETE CASCADE,
    CONSTRAINT notebook_source_chunks_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_notebook_source_chunks_source ON notebook_source_chunks (source_id, ordinal);
CREATE INDEX IF NOT EXISTS idx_notebook_source_chunks_search ON notebook_source_chunks USING gin (search);

CREATE TABLE IF NOT EXISTS notebook_study_outputs (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    page_id uuid NOT NULL,
    -- Mirrors StudyOutputKind.
    -- squawk-ignore prefer-text-field
    kind varchar(16) NOT NULL,
    -- squawk-ignore prefer-text-field
    title varchar(255) NOT NULL,
    -- Markdown for SUMMARY and STUDY_GUIDE, JSON for QUIZ and OVERVIEW.
    content text NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    score integer,
    -- squawk-ignore prefer-bigint-over-int
    total integer,
    -- When a quiz was first passed. The 20 XP is paid on that transition only.
    passed_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT notebook_study_outputs_pkey PRIMARY KEY (id),
    CONSTRAINT notebook_study_outputs_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT notebook_study_outputs_page_fkey FOREIGN KEY (page_id)
        REFERENCES notebook_pages (id) ON DELETE CASCADE,
    CONSTRAINT notebook_study_outputs_kind_check CHECK (kind IN ('OVERVIEW', 'SUMMARY', 'STUDY_GUIDE', 'QUIZ'))
);

CREATE INDEX IF NOT EXISTS idx_notebook_study_outputs_page ON notebook_study_outputs (page_id, created_at);

CREATE TABLE IF NOT EXISTS notebook_chat_messages (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    page_id uuid NOT NULL,
    -- squawk-ignore prefer-text-field
    role varchar(16) NOT NULL,
    content text NOT NULL,
    -- The citations an answer carries, as JSON. Null on the person's own messages.
    citations text,
    created_at timestamptz NOT NULL,
    CONSTRAINT notebook_chat_messages_pkey PRIMARY KEY (id),
    CONSTRAINT notebook_chat_messages_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT notebook_chat_messages_page_fkey FOREIGN KEY (page_id)
        REFERENCES notebook_pages (id) ON DELETE CASCADE,
    CONSTRAINT notebook_chat_messages_role_check CHECK (role IN ('USER', 'ASSISTANT'))
);

CREATE INDEX IF NOT EXISTS idx_notebook_chat_messages_page ON notebook_chat_messages (page_id, created_at);

-- A focus cycle can be run on a notebook page, so a node knows how long was spent on it.
-- SET NULL: deleting a page must not erase the fact that somebody focused for 25 minutes.
ALTER TABLE focus_cycles ADD COLUMN IF NOT EXISTS notebook_page_id uuid;

-- The column was added one statement ago and is NULL on every row, so validating the foreign
-- key scans nothing worth measuring (the V30 reasoning, per line as squawk reads it).
-- squawk-ignore constraint-missing-not-valid, adding-foreign-key-constraint, prefer-robust-stmts
ALTER TABLE focus_cycles ADD CONSTRAINT focus_cycles_notebook_page_fkey FOREIGN KEY (notebook_page_id) REFERENCES notebook_pages (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_focus_cycles_notebook_page ON focus_cycles (notebook_page_id);

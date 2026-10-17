-- Quick notes: the "Ideas" board, post-its a person drops ideas on to get them out of their head.
--
-- A free board, not a list. Each note keeps the spot it was dropped on (x, y, its top-left
-- corner in board units) and a stacking order (z, higher is in front), so the board reads the
-- same on every device. A note created without a spot goes to the first free cell of a fixed
-- grid; QuickNotePlacement holds that rule, and the grid's geometry lives there.
--
-- Archiving is a timestamp, not a second table: an archived note keeps its text, colour and
-- position, so restoring it puts it back where it was. The 200-active-notes limit counts
-- `archived_at IS NULL` rows only, which is what the (user_id, archived_at) index serves.
--
-- `converted_task_id` remembers that the note became a task. ON DELETE SET NULL because the
-- task has a life of its own after that: deleting it, or the one-time-task cleanup deleting it,
-- must not be blocked by an archived post-it, and the note simply stops pointing at it.
--
-- ON DELETE CASCADE from users, like calendar_tasks (V40): an idea board has no meaning past the
-- account that wrote it. The deletion tests seed a row here and assert it is gone.
--
-- SET LOCAL, not SET: see V13/V14/V19/V26/V27/V31/V40. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS quick_notes (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- A post-it, not a page: the notebook is where long writing goes. The cap is in the column
    -- so a client that skips the DTO cannot store more than the board can draw.
    -- squawk-ignore prefer-text-field
    text varchar(500) NOT NULL,
    -- Mirrors QuickNoteColor. Adding a value in Java without adding it here makes every write of
    -- the new colour fail at insert time.
    -- squawk-ignore prefer-text-field
    color varchar(16) DEFAULT 'YELLOW' NOT NULL,
    -- Board coordinates. `integer` and not `bigint` because the entity fields are ints and
    -- validate refuses int8 against INTEGER (V31 has the same note). The CHECKs mirror
    -- QuickNote.COORD_LIMIT and the DTOs' @Min/@Max.
    -- squawk-ignore prefer-bigint-over-int
    x integer NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    y integer NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    z integer DEFAULT 0 NOT NULL,
    archived_at timestamptz,
    converted_task_id uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT quick_notes_pkey PRIMARY KEY (id),
    CONSTRAINT quick_notes_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT quick_notes_converted_task_fkey FOREIGN KEY (converted_task_id)
        REFERENCES tasks (id) ON DELETE SET NULL,
    CONSTRAINT quick_notes_color_check
        CHECK (color IN ('YELLOW', 'PINK', 'BLUE', 'GREEN', 'PURPLE', 'ORANGE')),
    -- Mirrors QuickNote.COORD_LIMIT. Widening it in Java without widening it here makes every
    -- write of the new value fail at insert time.
    CONSTRAINT quick_notes_x_check CHECK (x BETWEEN -100000 AND 100000),
    CONSTRAINT quick_notes_y_check CHECK (y BETWEEN -100000 AND 100000)
);

-- Every read the clients make: this user's active notes (archived_at IS NULL), or their archived
-- ones newest first. The same index answers the limit count.
CREATE INDEX IF NOT EXISTS idx_quick_notes_user_archived ON quick_notes (user_id, archived_at);

-- Deleting a task has to find the notes pointing at it to null them. Without an index that is a
-- scan of every account's notes on every task delete. Partial, because most notes never become
-- a task.
CREATE INDEX IF NOT EXISTS idx_quick_notes_converted_task ON quick_notes (converted_task_id)
    WHERE converted_task_id IS NOT NULL;

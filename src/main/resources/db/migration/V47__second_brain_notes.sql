-- Second Brain, step 2 (readable memory): what Beyou keeps about the things a person tracks,
-- as dated notes they can read, correct and delete on the Second Brain page.
--
-- second_brain_notes: one short line about one subject. The subject is a habit, a goal, a
-- routine or a category (subject_id is that row's id), or LOOSE when the thing it was about
-- is gone and has nowhere to go (subject_id null). There is no foreign key from subject_id:
-- it points at one of four tables, and deleting the thing must not delete what the person
-- said about it. The services move the notes instead, in the same transaction as the delete
-- (SecondBrainHooks): a habit or goal hands them to its first category by name, and a routine
-- or a category turns them LOOSE. former_type says what the thing was once its notes moved,
-- and subject_name keeps its name, so the page can still say "Gym, a habit you deleted".
--
-- origin says who wrote the line: the person (USER), an answer on the page (ANSWER) or the
-- assistant in a chat (ASSISTANT), the same names profile facts use.
--
-- second_brain_questions gains the subject a NEW_THING question asks about, so the question
-- is asked once per thing and goes away when the thing is deleted before it was answered.
--
-- second_brain_facts gains two origins: ASSISTANT (the chat saved the line) and OLD_NOTE (the
-- person kept a sentence of the hidden note the assistant wrote before this step).
--
-- ON DELETE CASCADE from users, like the V45 tables: a note has no meaning past the account
-- it describes. The deletion tests seed a row here and assert it is gone.
--
-- SET LOCAL, not SET: see V13/V14/V19/V26/V27/V31/V40/V42/V45. Flyway has no datasource of its
-- own, so a session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS second_brain_notes (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- Mirrors SecondBrainSubjectType. Adding a value in Java without adding it here makes every
    -- write of the new type fail at insert time.
    -- squawk-ignore prefer-text-field
    subject_type varchar(16) NOT NULL,
    subject_id uuid,
    -- The thing's name when the note was written, and its last name once the thing is gone.
    -- 256 is the widest name a habit, goal, routine or category column holds.
    -- squawk-ignore prefer-text-field
    subject_name varchar(256) NOT NULL,
    -- Mirrors SecondBrainSubjectType too, limited to the three things whose notes can move.
    -- squawk-ignore prefer-text-field
    former_type varchar(16),
    -- A note, not a page. The cap is in the column so a client that skips the DTO, or a model
    -- that ignores its prompt, cannot store more than a prompt should carry.
    -- squawk-ignore prefer-text-field
    text varchar(500) NOT NULL,
    -- Mirrors SecondBrainNoteOrigin, same warning as subject_type.
    -- squawk-ignore prefer-text-field
    origin varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT second_brain_notes_pkey PRIMARY KEY (id),
    CONSTRAINT second_brain_notes_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT second_brain_notes_subject_type_check
        CHECK (subject_type IN ('HABIT', 'GOAL', 'ROUTINE', 'CATEGORY', 'LOOSE')),
    -- A LOOSE note is the only kind with no thing to point at, and it never points at one.
    CONSTRAINT second_brain_notes_subject_id_check CHECK ((subject_type = 'LOOSE') = (subject_id IS NULL)),
    CONSTRAINT second_brain_notes_former_type_check
        CHECK (former_type IS NULL OR former_type IN ('HABIT', 'GOAL', 'ROUTINE')),
    CONSTRAINT second_brain_notes_origin_check CHECK (origin IN ('USER', 'ANSWER', 'ASSISTANT'))
);

-- The page reads every note of one user. The 30-per-thing trim and the moves on delete read
-- one subject of one user.
CREATE INDEX IF NOT EXISTS idx_second_brain_notes_user_subject
    ON second_brain_notes (user_id, subject_type, subject_id);

-- Nullable additions with no default: a catalogue-only change, no rewrite, no backfill.
-- Mirrors SecondBrainSubjectType, limited by the CHECK below to what a question can be about.
-- squawk-ignore prefer-text-field
ALTER TABLE second_brain_questions ADD COLUMN IF NOT EXISTS subject_type varchar(16);
ALTER TABLE second_brain_questions ADD COLUMN IF NOT EXISTS subject_id uuid;

-- The constraint is new, so this drops nothing; it lets a rerun after a failure part way through
-- add it again instead of stopping on a duplicate.
-- squawk-ignore ban-drop-constraint
ALTER TABLE second_brain_questions DROP CONSTRAINT IF EXISTS second_brain_questions_subject_check;
ALTER TABLE second_brain_questions ADD CONSTRAINT second_brain_questions_subject_check
    CHECK (subject_type IS NULL OR subject_type IN ('HABIT', 'GOAL', 'ROUTINE')) NOT VALID;
ALTER TABLE second_brain_questions VALIDATE CONSTRAINT second_brain_questions_subject_check;

-- Mirrors SecondBrainQuestionKind. Adding a value in Java without adding it here makes every
-- write of the new kind fail at insert time.
--
-- The two CHECKs below are widened, which Postgres can only do by dropping and adding them.
-- Nothing is lost in between: the drop and the add run in this migration's one transaction,
-- and the new list holds every value the old one did. NOT VALID then VALIDATE keeps the
-- ACCESS EXCLUSIVE lock to the catalogue change; the scan runs under a weaker lock.
-- squawk-ignore ban-drop-constraint
ALTER TABLE second_brain_questions DROP CONSTRAINT IF EXISTS second_brain_questions_kind_check;
ALTER TABLE second_brain_questions ADD CONSTRAINT second_brain_questions_kind_check
    CHECK (kind IN ('PROFILE_OBJECTIVES', 'PROFILE_LIKES', 'PROFILE_ABOUT', 'CHECK_IN', 'FOLLOW_UP',
                    'NEW_THING')) NOT VALID;
ALTER TABLE second_brain_questions VALIDATE CONSTRAINT second_brain_questions_kind_check;

-- Mirrors SecondBrainFactOrigin, same warning.
-- squawk-ignore ban-drop-constraint
ALTER TABLE second_brain_facts DROP CONSTRAINT IF EXISTS second_brain_facts_origin_check;
ALTER TABLE second_brain_facts ADD CONSTRAINT second_brain_facts_origin_check
    CHECK (origin IN ('USER', 'ANSWER', 'ASSISTANT', 'OLD_NOTE')) NOT VALID;
ALTER TABLE second_brain_facts VALIDATE CONSTRAINT second_brain_facts_origin_check;

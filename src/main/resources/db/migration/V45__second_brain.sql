-- Second Brain, step 1 (profile recognition): the page where a person sees what Beyou
-- understands about their life and steers it. Four tables, all owned by one account.
--
-- second_brain_sources: the switches for which Beyou data Second Brain may read. One row per
-- (user, source) the person has touched. A missing row means the default, which lives in
-- SecondBrainSource (routines, goals and mood on; calendar, notebook and ideas off), so nothing
-- is backfilled and a new source added later needs no rows either.
--
-- second_brain_facts: the small profile every chat should know. Short lines the person wrote
-- (origin USER) or that were learned from an interview answer (origin ANSWER). At most
-- SecondBrainFact.MAX_PER_USER per account, counted in the service under an advisory lock.
--
-- second_brain_questions: the interview. A question is asked once, stays OPEN until the person
-- answers or skips it, and is kept afterwards: the seed rule ("never ask this profile question
-- twice") and the follow-up prompt ("do not repeat yourself") both read the closed ones.
--
-- second_brain_insights: the generated overview, one row per account, replaced on refresh.
--
-- The mood journal has no column here and is never copied here. Mood levels are read at request
-- time and never stored by this feature.
--
-- ON DELETE CASCADE from users on all four, like quick_notes (V42): none of this has a meaning
-- past the account it describes. The deletion tests seed a row in each and assert it is gone.
--
-- SET LOCAL, not SET: see V13/V14/V19/V26/V27/V31/V40/V42. Flyway has no datasource of its own,
-- so a session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS second_brain_sources (
    user_id uuid NOT NULL,
    -- Mirrors SecondBrainSource. Adding a value in Java without adding it here makes every
    -- write of the new source fail at insert time.
    -- squawk-ignore prefer-text-field
    source varchar(24) NOT NULL,
    enabled boolean NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT second_brain_sources_pkey PRIMARY KEY (user_id, source),
    CONSTRAINT second_brain_sources_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT second_brain_sources_source_check
        CHECK (source IN ('ROUTINES', 'GOALS', 'MOOD', 'CALENDAR', 'NOTEBOOK', 'IDEAS'))
);

CREATE TABLE IF NOT EXISTS second_brain_facts (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- Mirrors SecondBrainFactKind. Adding a value in Java without adding it here makes every
    -- write of the new kind fail at insert time.
    -- squawk-ignore prefer-text-field
    kind varchar(16) NOT NULL,
    -- One line, not a page. The cap is in the column so a client that skips the DTO, or a model
    -- that ignores its prompt, cannot store more than a chat prompt should carry.
    -- squawk-ignore prefer-text-field
    text varchar(200) NOT NULL,
    -- Mirrors SecondBrainFactOrigin, same warning as kind.
    -- squawk-ignore prefer-text-field
    origin varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT second_brain_facts_pkey PRIMARY KEY (id),
    CONSTRAINT second_brain_facts_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT second_brain_facts_kind_check CHECK (kind IN ('OBJECTIVE', 'LIKE', 'ABOUT')),
    CONSTRAINT second_brain_facts_origin_check CHECK (origin IN ('USER', 'ANSWER'))
);

-- Every read is "this user's facts", and so is the count behind the limit.
CREATE INDEX IF NOT EXISTS idx_second_brain_facts_user ON second_brain_facts (user_id);

CREATE TABLE IF NOT EXISTS second_brain_questions (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- Mirrors SecondBrainQuestionKind. Adding a value in Java without adding it here makes
    -- every write of the new kind fail at insert time.
    -- squawk-ignore prefer-text-field
    kind varchar(24) NOT NULL,
    -- squawk-ignore prefer-text-field
    text varchar(300) NOT NULL,
    -- Mirrors SecondBrainQuestionStatus, same warning as kind.
    -- squawk-ignore prefer-text-field
    status varchar(12) DEFAULT 'OPEN' NOT NULL,
    -- What the person wrote back. Null while OPEN and for a SKIPPED question.
    -- squawk-ignore prefer-text-field
    answer varchar(2000),
    created_at timestamptz NOT NULL,
    answered_at timestamptz,
    CONSTRAINT second_brain_questions_pkey PRIMARY KEY (id),
    CONSTRAINT second_brain_questions_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT second_brain_questions_kind_check
        CHECK (kind IN ('PROFILE_OBJECTIVES', 'PROFILE_LIKES', 'PROFILE_ABOUT', 'CHECK_IN', 'FOLLOW_UP')),
    CONSTRAINT second_brain_questions_status_check CHECK (status IN ('OPEN', 'ANSWERED', 'SKIPPED'))
);

-- The page reads this user's OPEN questions, and the refill counts them against the limit.
CREATE INDEX IF NOT EXISTS idx_second_brain_questions_user_status
    ON second_brain_questions (user_id, status);

CREATE TABLE IF NOT EXISTS second_brain_insights (
    user_id uuid NOT NULL,
    -- The service stops the model at SecondBrainInsight.TEXT_MAX; the column leaves headroom
    -- so raising that cap a little is not a migration.
    -- squawk-ignore prefer-text-field
    text varchar(1200) NOT NULL,
    -- Mirrors SecondBrainVerdict. Adding a value in Java without adding it here makes every
    -- write of the new verdict fail at insert time.
    -- squawk-ignore prefer-text-field
    verdict varchar(16) NOT NULL,
    generated_at timestamptz NOT NULL,
    CONSTRAINT second_brain_insights_pkey PRIMARY KEY (user_id),
    CONSTRAINT second_brain_insights_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT second_brain_insights_verdict_check
        CHECK (verdict IN ('ON_COURSE', 'DRIFTING', 'TOO_EARLY'))
);

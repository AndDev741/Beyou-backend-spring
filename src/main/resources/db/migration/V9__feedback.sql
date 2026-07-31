-- User feedback submissions: a category, a free-text body, an internal triage
-- status, and the context the client captured automatically (originating
-- screen, app version, platform, language, active theme).
--
-- Squawk ignores (per line): category/status are bounded enum-like strings
-- mirroring the FeedbackCategory / FeedbackStatus enums (CHECK constraints keep
-- the two in step, same shape as goals.status in V1); the context columns are
-- bounded machine-captured values, clamped in FeedbackMapper before every save;
-- created_at/updated_at are written by the server clock, so naive timestamps
-- suffice — same rationale as V5/V7. New table on a pre-production database.
CREATE TABLE IF NOT EXISTS feedback (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- squawk-ignore prefer-text-field
    category varchar(32) NOT NULL,
    body text NOT NULL,
    -- squawk-ignore prefer-text-field
    status varchar(32) NOT NULL,
    -- squawk-ignore prefer-text-field
    context_screen varchar(200),
    -- squawk-ignore prefer-text-field
    context_app_version varchar(40),
    -- squawk-ignore prefer-text-field
    context_platform varchar(40),
    -- squawk-ignore prefer-text-field
    context_language varchar(20),
    -- squawk-ignore prefer-text-field
    context_theme varchar(40),
    -- squawk-ignore prefer-timestamp-tz
    created_at timestamp NOT NULL,
    -- squawk-ignore prefer-timestamp-tz
    updated_at timestamp NOT NULL,
    CONSTRAINT pk_feedback PRIMARY KEY (id),
    CONSTRAINT feedback_category_check CHECK (category IN ('BUG', 'FEATURE_REQUEST', 'OTHER')),
    CONSTRAINT feedback_status_check CHECK (status IN ('OPEN', 'TAKING_CARE', 'CLOSED')),
    -- Deleting an account takes its feedback with it, so account deletion can
    -- never be blocked by a foreign key it does not know about.
    CONSTRAINT fk_feedback_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Export / "my submissions" reads, newest first.
CREATE INDEX IF NOT EXISTS feedback_user_id_created_at_idx
ON feedback(user_id, created_at DESC);

-- Admin triage queue: open items, newest first.
CREATE INDEX IF NOT EXISTS feedback_status_created_at_idx
ON feedback(status, created_at DESC);

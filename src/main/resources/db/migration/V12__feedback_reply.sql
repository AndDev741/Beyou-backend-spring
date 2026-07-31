-- A written reply from the admin to a feedback submission (R14/KD4).
--
-- Only a reply speaks to the user: a status transition on the parent row
-- emits nothing, so this table is the single trigger for user-facing mail.
-- Email is the return channel (KD3) — there is no in-app inbox — so these
-- rows exist for the admin's own history, not for a client to read back.
--
-- author_id is nullable ON DELETE SET NULL rather than cascading: removing an
-- admin account must neither be blocked nor erase replies already sent to
-- other people. The parent cascade mirrors V9/V10 so account deletion stays
-- unblockable.
--
-- Squawk ignores (per line): created_at is written by the server clock, so a
-- naive timestamp suffices — same rationale as V5/V7/V9/V10. New table on a
-- pre-production database.
CREATE TABLE IF NOT EXISTS feedback_reply (
    id uuid NOT NULL,
    feedback_id uuid NOT NULL,
    author_id uuid,
    body text NOT NULL,
    -- squawk-ignore prefer-timestamp-tz
    created_at timestamp NOT NULL,
    CONSTRAINT pk_feedback_reply PRIMARY KEY (id),
    CONSTRAINT fk_feedback_reply_feedback FOREIGN KEY (feedback_id)
        REFERENCES feedback(id) ON DELETE CASCADE,
    CONSTRAINT fk_feedback_reply_author FOREIGN KEY (author_id)
        REFERENCES users(id) ON DELETE SET NULL
);

-- A submission's reply thread, oldest first.
CREATE INDEX IF NOT EXISTS feedback_reply_feedback_id_created_at_idx
ON feedback_reply(feedback_id, created_at);

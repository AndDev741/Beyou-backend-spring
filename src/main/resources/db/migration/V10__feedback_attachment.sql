-- Images attached to a feedback submission: an automatic screenshot of a
-- failed screen (R9) plus anything the user adds by hand (R3).
--
-- The row is only an index — the bytes live on disk under
-- {upload-dir}/feedback-attachments/{feedback_id}/{id}.jpg, written by
-- FeedbackAttachmentStorageService. There is no content-type column because
-- every upload is re-encoded to JPEG, so a stored type could only ever
-- disagree with the file. Dimensions are the STORED ones (post-downscale) so
-- the admin console can lay attachments out without touching the disk.
--
-- Squawk ignores (per line): created_at is written by the server clock, so a
-- naive timestamp suffices — same rationale as V5/V7/V9. New table on a
-- pre-production database.
CREATE TABLE IF NOT EXISTS feedback_attachment (
    id uuid NOT NULL,
    feedback_id uuid NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    width integer NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    height integer NOT NULL,
    size_bytes bigint NOT NULL,
    -- squawk-ignore prefer-timestamp-tz
    created_at timestamp NOT NULL,
    CONSTRAINT pk_feedback_attachment PRIMARY KEY (id),
    -- Dimensions and size come from a re-encode the server performed itself,
    -- so anything non-positive means the write path is broken, not the input.
    CONSTRAINT feedback_attachment_width_check CHECK (width > 0),
    CONSTRAINT feedback_attachment_height_check CHECK (height > 0),
    CONSTRAINT feedback_attachment_size_bytes_check CHECK (size_bytes > 0),
    -- Deleting a submission takes its attachment rows with it, so account
    -- deletion (which already cascades feedback) can never be blocked here.
    CONSTRAINT fk_feedback_attachment_feedback FOREIGN KEY (feedback_id)
        REFERENCES feedback(id) ON DELETE CASCADE
);

-- Listing a submission's attachments, oldest first (the automatic screenshot
-- is attached before anything the user adds).
CREATE INDEX IF NOT EXISTS feedback_attachment_feedback_id_created_at_idx
ON feedback_attachment(feedback_id, created_at);

-- User-owned AI agent chats. The chat id is used as the Spring AI
-- conversation_id in SPRING_AI_CHAT_MEMORY (V4).
--
-- Squawk ignores (per line): title is a bounded UI string (varchar(255)
-- matches the entity mapping); created_at/updated_at order the chat list and
-- are always written by the server clock, so naive timestamps are sufficient.
-- New table on a pre-production database — same rationale as V2/V3.
CREATE TABLE IF NOT EXISTS chats (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- squawk-ignore prefer-text-field
    title varchar(255) NOT NULL,
    -- squawk-ignore prefer-timestamp-tz
    created_at timestamp NOT NULL,
    -- squawk-ignore prefer-timestamp-tz
    updated_at timestamp NOT NULL,
    CONSTRAINT pk_chats PRIMARY KEY (id),
    CONSTRAINT fk_chats_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS chats_user_id_updated_at_idx
ON chats(user_id, updated_at DESC);

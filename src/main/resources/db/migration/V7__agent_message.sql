-- Display history for agent chats: an ordered, structured transcript per turn.
-- Distinct from SPRING_AI_CHAT_MEMORY (V4), which is the MODEL's working memory
-- (window-limited, text-only). This table is what the UI renders: full history,
-- with the assistant turn stored as ordered segments (text interleaved with the
-- tools the agent used) in a JSON content string.
--
-- Squawk ignores (per line): role is a bounded enum-like string; content is a
-- JSON blob kept as text (app-side (de)serialization, DB-agnostic); created_at
-- orders within a turn and is written by the server clock. Cascade delete so
-- removing a chat cleans its transcript. New table on a pre-production database.
CREATE TABLE IF NOT EXISTS agent_message (
    id uuid NOT NULL,
    chat_id uuid NOT NULL,
    -- squawk-ignore prefer-text-field
    role varchar(10) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content text NOT NULL,
    sequence_id bigint NOT NULL,
    -- squawk-ignore prefer-timestamp-tz
    created_at timestamp NOT NULL,
    CONSTRAINT pk_agent_message PRIMARY KEY (id),
    CONSTRAINT fk_agent_message_chat FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS agent_message_chat_id_sequence_id_idx
ON agent_message(chat_id, sequence_id);

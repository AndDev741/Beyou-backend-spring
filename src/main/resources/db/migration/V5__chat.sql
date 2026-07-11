-- User-owned AI agent chats. The chat id is used as the Spring AI
-- conversation_id in SPRING_AI_CHAT_MEMORY (V4).

CREATE TABLE IF NOT EXISTS chats (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    title varchar(255) NOT NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    CONSTRAINT pk_chats PRIMARY KEY (id),
    CONSTRAINT fk_chats_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS chats_user_id_updated_at_idx
ON chats(user_id, updated_at DESC);

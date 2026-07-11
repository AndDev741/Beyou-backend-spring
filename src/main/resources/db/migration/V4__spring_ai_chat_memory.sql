-- Vendored from org/springframework/ai/chat/memory/repository/jdbc/schema-postgresql.sql
-- (spring.ai.chat.memory.repository.jdbc.initialize-schema=never — Flyway owns the schema).
--
-- Squawk ignores (per line — squawk 2.x attaches ignores to the next line):
-- this DDL must stay compatible with what JdbcChatMemoryRepository expects
-- (varchar ids/types, naive timestamp). The timestamp column is ordering-only
-- and always written by the server clock, so the missing UTC offset carries
-- no information here.
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    -- squawk-ignore prefer-text-field
    conversation_id VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    -- squawk-ignore prefer-text-field
    type VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    -- squawk-ignore prefer-timestamp-tz
    "timestamp" TIMESTAMP NOT NULL,
    sequence_id BIGINT NOT NULL
    );

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
ON SPRING_AI_CHAT_MEMORY(conversation_id, "timestamp");

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX
ON SPRING_AI_CHAT_MEMORY(conversation_id, sequence_id);

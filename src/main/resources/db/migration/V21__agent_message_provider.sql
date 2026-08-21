-- Which LLM provider produced an assistant turn.
--
-- The agent runs behind FallbackChatModel, an ordered chain of providers
-- (LLM_CHAIN_ORDER, today mistral,gemini,glm,deepseek). Whichever link answers
-- first wins, so tool discipline — whether the model invents ids, whether it
-- claims a write it never attempted — varies by turn with no record of which
-- model was responsible. beyou.ai.llm.calls counts calls per provider, but a
-- counter cannot tell you which provider produced a specific bad answer, which
-- is exactly what you need when tuning a prompt against a reported incident.
--
-- Nullable on purpose: every row written before this column existed genuinely
-- has no known provider, and user turns never have one. varchar rather than an
-- enum because the chain is configuration — a provider can be added or removed
-- via LLM_CHAIN_ORDER without a migration.
--
-- SET LOCAL, not SET — see V13/V14/V20. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- Adding a nullable column with no default rewrites nothing: the catalog records it
-- and existing rows are read as null, so this takes a brief ACCESS EXCLUSIVE lock and
-- returns. The lock_timeout above is the backstop if it ever queues behind live
-- traffic. varchar(32) rather than text to match the other short bounded strings in
-- this schema (agent_message.role, entity_check_day.owner_type).
-- squawk-ignore prefer-text-field
ALTER TABLE agent_message ADD COLUMN IF NOT EXISTS provider varchar(32);

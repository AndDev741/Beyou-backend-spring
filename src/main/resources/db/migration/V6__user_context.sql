-- AI agent context memory blobs, overwritten by the model via the
-- updateGlobalContext / updateChatContext tools.
--
-- Squawk ignore: the varchar caps ARE the product limit (they bound prompt
-- token cost and prompt-injection persistence) and are also clamped in
-- ChatService before every save; a future resize would be a deliberate
-- product change on small tables — same pre-production rationale as V2/V3.
-- squawk-ignore prefer-text-field
ALTER TABLE chats ADD COLUMN IF NOT EXISTS user_context_in_chat varchar(1000);

-- squawk-ignore prefer-text-field
ALTER TABLE users ADD COLUMN IF NOT EXISTS user_context varchar(2000);

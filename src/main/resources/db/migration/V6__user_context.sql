ALTER TABLE chats ADD COLUMN user_context_in_chat varchar(1000);
ALTER TABLE users ADD COLUMN user_context varchar(2000);

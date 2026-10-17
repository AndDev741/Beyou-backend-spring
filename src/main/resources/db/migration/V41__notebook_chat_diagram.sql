-- A diagram on a study-room answer.
--
-- The tutor can now draw: when the person asks to see something, or a process, a sequence or a
-- hierarchy is easier to see than to read, its answer carries a diagram spec (kind, title,
-- nodes, links) that the clients lay out and draw. The spec is stored here as JSON, after
-- DiagramSanitizer has cleaned it, so the conversation reads back with its pictures.
--
-- Nullable with no default: questions never have one, and neither does any answer written
-- before this. text, like content and citations beside it, because a spec of 24 nodes with their
-- details runs to a few kilobytes.
--
-- SET LOCAL, not SET. See V13/V14/V19/V26/V27: Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE notebook_chat_messages ADD COLUMN IF NOT EXISTS diagram text;

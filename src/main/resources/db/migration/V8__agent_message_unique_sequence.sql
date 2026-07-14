-- Make (chat_id, sequence_id) unique so a non-atomic sequence assignment can
-- never silently write duplicate ordinals (which would render the transcript in
-- a nondeterministic order). The sequence is now assigned under a per-chat
-- advisory lock (AgentMessageService), so collisions shouldn't happen — this is
-- defense in depth that fails loudly if one ever slips through.
--
-- The unique index also serves findByChatIdOrderBySequenceIdAsc, so the plain
-- index from V7 is redundant and dropped. Pre-production table (only exists on
-- this branch) — no backfill/dedupe needed, and the ACCESS EXCLUSIVE locks these
-- statements take are instant on an empty/tiny table (same rationale as V2/V7).
-- Squawk ignores are per-line (attach to the next line).

ALTER TABLE agent_message DROP CONSTRAINT IF EXISTS uq_agent_message_chat_sequence;

-- squawk-ignore require-concurrent-index-deletion
DROP INDEX IF EXISTS agent_message_chat_id_sequence_id_idx;

-- squawk-ignore constraint-missing-not-valid, disallowed-unique-constraint
ALTER TABLE agent_message ADD CONSTRAINT uq_agent_message_chat_sequence UNIQUE (chat_id, sequence_id);

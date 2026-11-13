-- Retire agent_message.provider.
--
-- V21 added the column to record which link of the backend's LLM fallback chain
-- answered a turn. That chain is gone: the backend now calls one OpenAI-compatible
-- endpoint, and choosing a provider is the work of the gateway behind it. The backend
-- has nothing left to write here, and the gateway's own logs say which provider served
-- a request.
--
-- The stored values go with the column. For a turn served through the gateway the
-- value was only ever "generic", the name of the chain slot that pointed at it, so the
-- history worth anything is from before the gateway.
--
-- One reader outside this repo used the column: the "AI agent messages per day, by
-- provider" panel on the Grafana Product dashboard, which queries this table directly.
-- Its query is changed in Beyou-dev-env in the same batch, and the copy that production
-- Grafana loads has to change with it or that one panel errors.
--
-- SET LOCAL, not SET. See V13/V14/V20. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- Squawk ignore: ban-drop-column. The rule guards a deployed reader that still selects
-- the column. The only application reference was AgentMessage.provider, removed in the
-- same change, and Hibernate runs ddl-auto: validate everywhere, so a stale mapping
-- fails at boot. The backend runs as a single replica that is stopped before its
-- replacement starts, so no old instance is alive while this runs. No view, index,
-- constraint or trigger names the column (V21 declares it as a bare nullable varchar).
--
-- The cost is on rollback: the previous image maps this column and will refuse to boot
-- until it is added back with
--   ALTER TABLE agent_message ADD COLUMN provider varchar(32);
--
-- DROP COLUMN is metadata-only in PostgreSQL, so this takes an ACCESS EXCLUSIVE lock
-- for the length of a catalogue update and no table rewrite.
ALTER TABLE agent_message
    -- squawk-ignore ban-drop-column
    DROP COLUMN IF EXISTS provider;

-- An index leading on user_id for the six notebook tables V34 created without one.
--
-- Every one of these tables cascades from users (ON DELETE CASCADE on user_id), so deleting an
-- account has to find that account's rows in each of them. With no index leading on user_id that
-- is a sequential scan per table, and the source chunks table grows by a book's worth of rows per
-- PDF. The other user-owned tables already have one (V2 for the original domains, V27 focus, V29
-- federated identities, V31 mood, and in V34 itself notebook_pages, notebook_cards and
-- notebook_card_reviews). These six were missed.
--
-- The data export reads sources and chat messages per user in created order
-- (UserExportService), so those indexes carry created_at as a second column and serve the
-- ORDER BY too. Study outputs get the same shape, since any per-user list of them is by date.
-- The rest only ever need the lookup.
--
-- Plain CREATE INDEX, not CONCURRENTLY, for the reason V2 and V22 give: CONCURRENTLY cannot run
-- inside Flyway's transaction, and these tables are small everywhere this has to run.
--
-- SET LOCAL, not SET. See V13/V14/V19/V26/V27: Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE INDEX IF NOT EXISTS idx_notebook_board_nodes_user ON notebook_board_nodes (user_id);
CREATE INDEX IF NOT EXISTS idx_notebook_board_edges_user ON notebook_board_edges (user_id);
CREATE INDEX IF NOT EXISTS idx_notebook_sources_user ON notebook_sources (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_notebook_source_chunks_user ON notebook_source_chunks (user_id);
CREATE INDEX IF NOT EXISTS idx_notebook_study_outputs_user ON notebook_study_outputs (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_notebook_chat_messages_user ON notebook_chat_messages (user_id, created_at);

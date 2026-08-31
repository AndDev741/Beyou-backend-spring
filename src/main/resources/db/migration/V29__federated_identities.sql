-- One row per external identity a beyou account may be entered through.
--
-- Until now the only external provider was Google, and both of its entry points
-- found-or-created the account by EMAIL. That holds only while the issuer is one
-- that actually proves ownership of the address it asserts. The moment a second
-- provider is added, email stops being an identity: whoever operates that issuer
-- can mint a token claiming any address, and every beyou account becomes
-- reachable through it — including accounts belonging to people who never heard
-- of that provider.
--
-- So identity moves here, to the pair the issuer alone controls and cannot
-- reassign: (issuer, subject). Email survives only as email_at_link, a record of
-- what was claimed at the time, for support and audit. Nothing authenticates on it.
--
-- Google is NOT backfilled. We never stored its subject, so there is nothing to
-- backfill from; FederatedIdentityService writes the row on the next Google
-- sign-in instead. Rows created before that keep working through the email path,
-- which stays safe for Google specifically because Google verifies its addresses.
--
-- Squawk ignores (per line): issuer/subject/email_at_link are bounded external
-- identifiers, not free text — issuer is a URL capped by the OIDC spec's practical
-- limits, subject is capped at 255 by the same, and 320 is the RFC 5321 maximum for
-- an address; created_at/last_login_at are written by the server clock, so naive
-- timestamps suffice, same rationale as V5/V7/V9. New table on a pre-production database.
-- SET LOCAL, not SET — see V13/V14. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS federated_identities (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- squawk-ignore prefer-text-field
    issuer varchar(255) NOT NULL,
    -- squawk-ignore prefer-text-field
    subject varchar(255) NOT NULL,
    -- What the issuer claimed the address was when this link was made. Kept for
    -- support ("which account is this?") and audit ("did the claimed address
    -- change?"). Never read to find a user.
    -- squawk-ignore prefer-text-field
    email_at_link varchar(320),
    -- squawk-ignore prefer-timestamp-tz
    created_at timestamp NOT NULL,
    -- squawk-ignore prefer-timestamp-tz
    last_login_at timestamp,
    CONSTRAINT pk_federated_identities PRIMARY KEY (id),
    -- The whole point of the table. One external identity resolves to at most one
    -- beyou account, and the database refuses any code path that forgets it.
    CONSTRAINT federated_identities_issuer_subject_key UNIQUE (issuer, subject),
    -- Deleting an account takes its links with it, so account deletion can never
    -- be blocked by a foreign key it does not know about. Same shape as V9.
    CONSTRAINT fk_federated_identities_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- "Which providers is this account linked to?" — the settings screen, and the
-- guard that refuses to unlink the last way into an account with no password.
CREATE INDEX IF NOT EXISTS federated_identities_user_id_idx
ON federated_identities(user_id);

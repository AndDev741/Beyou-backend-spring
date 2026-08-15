-- Codes that let someone prove the account being deleted is theirs.
--
-- Deleting an account is the one action in BeYou with nothing behind it: no undo,
-- no grace period, no backup the user can reach. So it is not enough that the
-- session is valid — an unlocked phone left on a table is a valid session. The
-- flow asks the email account to agree too: BeYou mails a six-digit code and the
-- app will not delete anything until that code comes back.
--
-- Shaped after password_reset_tokens (V1), for the same reasons and with the same
-- properties: the code is stored only as a BCrypt hash, it expires, and it is
-- single-use through used_at. What it adds is `attempts`, because six digits is a
-- million guesses rather than a 256-bit token, so the code has to die after a few
-- wrong tries instead of waiting out its TTL.
--
-- user_id CASCADEs, unlike password_reset_tokens.user_id. That table's plain
-- foreign key blocks the very delete this one exists to perform; see the note on
-- UserService.deleteUser. A code has no meaning once the account is gone.
--
-- SET LOCAL, not SET — see V13/V14. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS account_deletion_codes (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- squawk-ignore prefer-text-field
    code_hash varchar(255) NOT NULL,
    -- This counts to five and then the code is dead, so a bigint would be eight
    -- bytes holding a number that never reaches six.
    -- squawk-ignore prefer-bigint-over-int
    attempts integer NOT NULL DEFAULT 0,
    -- squawk-ignore prefer-timestamp-tz
    created_at timestamp NOT NULL,
    -- squawk-ignore prefer-timestamp-tz
    expires_at timestamp NOT NULL,
    -- squawk-ignore prefer-timestamp-tz
    used_at timestamp,
    CONSTRAINT pk_account_deletion_codes PRIMARY KEY (id),
    CONSTRAINT fk_account_deletion_codes_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
);

-- The service only ever reads the newest code for one user.
CREATE INDEX IF NOT EXISTS account_deletion_codes_user_created_idx
    ON account_deletion_codes(user_id, created_at DESC);

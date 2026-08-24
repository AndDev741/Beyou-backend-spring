-- Who may be sent an engagement nudge, and the token that lets them say no.
--
-- Phase 1 of the engagement work. The nudges themselves (streak at risk, the XP
-- recovery window closing, the never-activated sequence) are not transactional
-- mail: nobody asked for them by submitting a form. So before a single one is
-- sent there has to be a switch the user owns and a way to flip it from inside
-- the mail, without logging in. That is this table.
--
-- A table rather than two more columns on `users`, which is where preferences
-- would naively go. `users` is loaded in full by SecurityFilter on EVERY
-- authenticated request; every column added there is read a few thousand times a
-- day to answer a question asked by a nightly job. Keeping it separate also gives
-- the token its own unique index, which is what the by-token lookup needs.
--
-- SET LOCAL, not SET — see V13/V14. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS notification_preferences (
    -- The user IS the identity here: one row per account, so user_id carries the
    -- primary key instead of a surrogate id sitting beside a unique constraint.
    user_id uuid NOT NULL,

    -- Engagement mail, as one switch. Deliberately not one flag per nudge type:
    -- three checkboxes for three triggers that do not exist yet is a schema
    -- written against a guess, and splitting one boolean later is a far smaller
    -- migration than merging three.
    --
    -- Defaults to TRUE. These are first-party messages about the reader's own
    -- routine — the service telling you your streak is about to break, not an
    -- offer — and they carry one-click opt-out below. A default of FALSE would
    -- mean the feature reaches nobody who does not go looking for it in settings.
    engagement_email boolean NOT NULL DEFAULT true,

    -- The capability an unsubscribe link carries. Stored RAW, unlike
    -- password_reset_tokens (V1) and account_deletion_codes (V16), which keep only
    -- a BCrypt hash — and the difference is deliberate, not an oversight.
    --
    -- Those two are one-shot secrets: minted, mailed, used once, dead. This one is
    -- STABLE, because every nudge for the rest of the account's life links to it.
    -- A hash cannot be un-hashed to build that link, so hashing would force a new
    -- token on every send: a write per mail, and every previously-sent mail's
    -- unsubscribe link silently dead. The worst a leak of this column allows is
    -- unsubscribing someone from mail they can re-enable in settings, which does
    -- not justify that trade.
    --
    -- 256 bits from SecureRandom, url-safe base64. Unguessable is the only
    -- property required: the token is not derived from the address, so possessing
    -- one reveals no account and holding a wrong one reveals nothing at all.
    -- squawk-ignore prefer-text-field
    unsubscribe_token varchar(64) NOT NULL,

    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    CONSTRAINT pk_notification_preferences PRIMARY KEY (user_id),
    CONSTRAINT uq_notification_preferences_token UNIQUE (unsubscribe_token),
    -- CASCADE, like account_deletion_codes and unlike password_reset_tokens: a
    -- preference has no meaning once the account is gone, and a plain foreign key
    -- here would block the account deletion it is attached to.
    CONSTRAINT fk_notification_preferences_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
);

-- No backfill, and no row for anyone until something needs one. An account with no
-- row reads as the default above — opted in, no token minted yet — so the nightly
-- job does not have to care whether settings were ever opened, and the token is
-- created at the moment the first mail needs to carry it. Backfilling would mint
-- a few hundred tokens today to save a single insert later.

package beyou.beyouapp.backend.user.dto;

import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;
import beyou.beyouapp.backend.user.enums.TimezoneSource;

public record UserResponseDTO(
        /**
         * The account's UUID. Sent so clients can identify the user to product
         * analytics (PostHog) by an opaque internal id instead of PII like the
         * email — the same no-PII posture the telemetry stack follows.
         */
        UUID id,
        String name,
        String email,
        String phrase,
        String phrase_author,
        int constance,
        /**
         * R20/KTD25 — true when nothing has been scheduled for this account in the last
         * {@code UserStreakService.DORMANT_AFTER_DAYS} days while {@code constance} is
         * still standing. The number is reported unchanged beside it: a paused run is not
         * a broken one, and deciding that from the raw last-check-in date is exactly the
         * judgement KTD25 keeps on this side of the wire.
         */
        boolean constanceDormant,
        String photo,
        boolean isGoogleAccount,
        List<String> widgetsId,
        String themeInUse,
        double xp,
        double actualLevelXp,
        double nextLevelXp,
        int level,
        ConstanceConfiguration constanceConfiguration,
        boolean constanceIncreaseToday,
        int maxConstance,
        boolean isTutorialCompleted,
        String languageInUse,
        String timezone,
        /**
         * Whether {@code timezone} was ever actually chosen. The clients read this to
         * decide whether they may adopt the device's zone over it: only {@code DEFAULT}
         * is adoptable. Sending it saves them from re-deriving the rule, and keeps the
         * rule itself in one place.
         */
        TimezoneSource timezoneSource,
        XpDecayStrategy xpDecayStrategy,
        /**
         * The day the account was created, as an ISO-8601 local date ({@code 2026-03-14}).
         *
         * <p>Sent for the same reason {@code id} is: the clients report it to product
         * analytics, as the person property that answers "how old is this account".
         * Nothing else can answer it — the analytics provider's own first-seen timestamp
         * is when it first <em>saw</em> the account, which for every account older than
         * the instrumentation is a different date entirely.
         *
         * <p>A date and not an instant because that is all there is to send: the column
         * behind it is a Postgres {@code date}, stamped by {@code User.onUserCreate} from
         * {@code LocalDate.now()} on the server's clock. So it is the server's calendar
         * day, not the user's, and it is precise to the day and no further — enough to
         * bucket an account by age, not enough to reason about signup <em>time</em>.
         *
         * <p>A {@code String} rather than a date type on purpose: the value is formatted
         * once, here, so the wire format cannot be moved by a Jackson date-serialization
         * setting somewhere else in the app, and both clients read an ISO-8601 date
         * without having to agree on anything further.
         */
        String createdAt
) {
}

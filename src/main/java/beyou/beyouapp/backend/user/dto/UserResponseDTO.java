package beyou.beyouapp.backend.user.dto;

import java.util.List;

import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;
import beyou.beyouapp.backend.user.enums.TimezoneSource;

public record UserResponseDTO(
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
        XpDecayStrategy xpDecayStrategy
) {
}

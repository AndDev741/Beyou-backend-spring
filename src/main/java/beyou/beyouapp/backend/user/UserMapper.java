package beyou.beyouapp.backend.user;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.checkday.UserStreakService;
import beyou.beyouapp.backend.domain.checkday.UserStreakService.UserStreak;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.user.dto.UserResponseDTO;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UserMapper {

    /**
     * R14 — the streak is no longer a property the entity can answer for itself. It is
     * counted in scheduled days, and which days were scheduled is stored in the account's
     * frozen check rows.
     */
    private final UserStreakService userStreakService;

    /**
     * The photo URL is the one field here nobody else can mint. It goes out signed,
     * because an {@code <img src>} cannot carry the JWT and the endpoint behind it
     * used to answer anyone who could guess a UUID.
     */
    private final PhotoUrlSigner photoUrlSigner;

    public UserResponseDTO toResponseDTO(User user){
        return toResponseDTO(user, null);
    }

    /**
     * @param photoVersion the local photo file's last-modified millis, or null
     *                     if the user has no uploaded photo. When present, the
     *                     photo URL is versioned so clients refresh their image
     *                     cache exactly when the photo changes.
     */
    public UserResponseDTO toResponseDTO(User user, Long photoVersion) {
        // Streak scalars are read against the owner's local day: a user checking in at 21:00
        // local must not see "not completed today" because the server already rolled over.
        LocalDate ownerToday = UserDateResolver.today(user);
        UserStreak streak = userStreakService.streakOf(user, ownerToday);

        String photo;
        if (photoVersion != null) {
            photo = "/api/v1/user/photo/" + user.getId()
                    + photoUrlSigner.signedQuery(user.getId(), photoVersion);
        } else {
            photo = user.getPerfilPhoto(); // null or Google CDN URL
        }
        return new UserResponseDTO(
            user.getId(),
            user.getName(),
            user.getEmail(),
            user.getPerfilPhrase(),
            user.getPerfilPhraseAuthor(),
            streak.currentStreak(),
            streak.dormant(),
            photo,
            user.isGoogleAccount(),
            user.getWidgetsIdInUse(),
            user.getThemeInUse(),
            user.getXpProgress().getXp(),
            user.getXpProgress().getActualLevelXp(),
            user.getXpProgress().getNextLevelXp(),
            user.getXpProgress().getLevel(),
            user.getConstanceConfiguration(),
            user.getCompletedDays().contains(ownerToday),
            user.getMaxConstance(),
            user.isTutorialCompleted(),
            user.getLanguageInUse(),
            user.getTimezone(),
            user.getTimezoneSource(),
            user.getXpDecayStrategy(),
            // toLocalDate(), not toInstant(): the field is a java.sql.Date, whose
            // toInstant() throws UnsupportedOperationException by contract. Null-guarded
            // because @PrePersist writes it on insert, so an entity assembled in memory
            // has none — and a mapper is not the place to blow up over that.
            user.getCreatedAt() == null ? null : user.getCreatedAt().toLocalDate().toString()
        );
    }
}

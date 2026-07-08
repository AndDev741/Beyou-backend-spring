package beyou.beyouapp.backend.user;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.user.dto.UserResponseDTO;

@Component
public class UserMapper {
    
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
        String photo;
        if (photoVersion != null) {
            photo = "/api/v1/user/photo/" + user.getId() + "?v=" + photoVersion;
        } else {
            photo = user.getPerfilPhoto(); // null or Google CDN URL
        }
        return new UserResponseDTO(
            user.getName(),
            user.getEmail(),
            user.getPerfilPhrase(),
            user.getPerfilPhraseAuthor(),
            user.getCurrentConstance(LocalDate.now()),
            photo,
            user.isGoogleAccount(),
            user.getWidgetsIdInUse(),
            user.getThemeInUse(),
            user.getXpProgress().getXp(),
            user.getXpProgress().getActualLevelXp(),
            user.getXpProgress().getNextLevelXp(),
            user.getXpProgress().getLevel(),
            user.getConstanceConfiguration(),
            user.getCompletedDays().contains(LocalDate.now()),
            user.getMaxConstance(),
            user.isTutorialCompleted(),
            user.getLanguageInUse(),
            user.getTimezone(),
            user.getXpDecayStrategy()
        );
    }
}

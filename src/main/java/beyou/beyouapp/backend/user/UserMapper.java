package beyou.beyouapp.backend.user;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.user.dto.UserResponseDTO;

@Component
public class UserMapper {
    
    public UserResponseDTO toResponseDTO(User user){
        return new UserResponseDTO(
            user.getName(),
            user.getEmail(),
            user.getPerfilPhrase(),
            user.getPerfilPhraseAuthor(),
            user.getCurrentConstance(LocalDate.now()),
            user.getPerfilPhoto(),
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
            user.getLanguageInUse()
        );
    }
}

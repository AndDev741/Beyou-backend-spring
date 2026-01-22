package beyou.beyouapp.backend.user.dto;

import java.util.List;

import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;

public record UserResponseDTO(
        String name,
        String email,
        String phrase,
        String phrase_author,
        int constance,
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
        int maxConstance
) {
}

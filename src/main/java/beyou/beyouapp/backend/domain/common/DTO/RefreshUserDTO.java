package beyou.beyouapp.backend.domain.common.DTO;

public record RefreshUserDTO(
    int currentConstance,
    boolean alreadyIncreaseConstanceToday,
    int maxConstance,
    double xp,
    int level,
    double actualLevelXp,
    double nextLevelXp
) {
    
}

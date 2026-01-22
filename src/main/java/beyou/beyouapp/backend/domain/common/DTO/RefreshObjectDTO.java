package beyou.beyouapp.backend.domain.common.DTO;

import java.util.UUID;

public record RefreshObjectDTO(
    UUID id,
    double xp,
    int level,
    double actualLevelXp,
    double nextLevelXp
) {
    
}

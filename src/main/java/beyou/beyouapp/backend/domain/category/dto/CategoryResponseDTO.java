package beyou.beyouapp.backend.domain.category.dto;

import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;


public record CategoryResponseDTO(UUID id, String name, String description, String iconId, Map<UUID, String> habits, Map<UUID, String> tasks, Map<UUID, String> goals, double xp,
double nextLevelXp, double actualLevelXp, int level, Date createdAt) {
    
}

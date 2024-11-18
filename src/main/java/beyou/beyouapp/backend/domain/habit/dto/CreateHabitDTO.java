package beyou.beyouapp.backend.domain.habit.dto;

import java.util.List;
import java.util.UUID;

public record CreateHabitDTO(UUID userId, String name, String description, String motivationalPhrase, 
String iconId, Integer importance, Integer dificulty, List<UUID> categoriesId, Integer xp, Integer level) {
    
}

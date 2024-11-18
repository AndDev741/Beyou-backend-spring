package beyou.beyouapp.backend.domain.habit.dto;

import java.util.List;
import java.util.UUID;


public record EditHabitDTO(UUID habitId, String name, String description, String motivationalPhrase, 
String iconId, int importance, int dificulty, List<UUID> categoriesId) {
    
}

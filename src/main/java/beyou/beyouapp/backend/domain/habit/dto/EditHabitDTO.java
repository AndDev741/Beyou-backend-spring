package beyou.beyouapp.backend.domain.habit.dto;

import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.category.Category;

public record EditHabitDTO(UUID habitId, String name, String description, String motivationalPhrase, 
String iconId, int importance, int dificulty, List<Category> categories) {
    
}

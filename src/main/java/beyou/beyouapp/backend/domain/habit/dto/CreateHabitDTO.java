package beyou.beyouapp.backend.domain.habit.dto;

import java.util.List;

import beyou.beyouapp.backend.domain.category.Category;

public record CreateHabitDTO(String userId, String name, String description, String motivationalPhrase, 
String iconId, int importance, int dificulty, List<Category> categories, int xp, int level) {
    
}

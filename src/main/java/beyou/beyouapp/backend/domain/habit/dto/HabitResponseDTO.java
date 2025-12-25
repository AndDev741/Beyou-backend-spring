package beyou.beyouapp.backend.domain.habit.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.category.Category;

public record HabitResponseDTO(UUID id ,String name, String description, String motivationalPhrase, 
String iconId, int importance, int dificulty, List<Category> categories, double xp, double actualLevelXp, double nextLevelXp, int level, int constance, LocalDate createdAt, LocalDate updatedAt) {
    
}

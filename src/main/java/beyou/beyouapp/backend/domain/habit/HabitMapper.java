package beyou.beyouapp.backend.domain.habit;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.habit.dto.EditHabitDTO;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.habit.dto.HabitResponseDTO;
import beyou.beyouapp.backend.user.User;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class HabitMapper {

    public Habit toEntity(CreateHabitDTO dto, List<Category> categories, XpByLevel actualBaseXp, XpByLevel nextLevelXp, User user) {
        List<Category> categoriesToUse = categories != null ? categories : Collections.emptyList();
        double nextXp = nextLevelXp != null ? nextLevelXp.getXp() : 0;
        double actualXp = actualBaseXp != null ? actualBaseXp.getXp() : 0;
        return new Habit(dto, new ArrayList<>(categoriesToUse), nextXp, actualXp, user);
    }

    public void updateEntity(Habit habit, EditHabitDTO dto, List<Category> categories) {
        habit.setName(dto.name());
        habit.setDescription(dto.description());
        habit.setIconId(dto.iconId());
        habit.setMotivationalPhrase(dto.motivationalPhrase());
        habit.setImportance(dto.importance());
        habit.setDificulty(dto.dificulty());
        habit.setCategories(new ArrayList<>(categories != null ? categories : Collections.emptyList()));
    }

    public HabitResponseDTO toResponseDTO(Habit habit) {
        XpProgress xpProgress = habit.getXpProgress();
        return new HabitResponseDTO(
                habit.getId(),
                habit.getName(),
                habit.getDescription(),
                habit.getMotivationalPhrase(),
                habit.getIconId(),
                habit.getImportance(),
                habit.getDificulty(),
                habit.getCategories(),
                xpProgress != null ? xpProgress.getXp() : 0,
                xpProgress != null ? xpProgress.getActualLevelXp() : 0,
                xpProgress != null ? xpProgress.getNextLevelXp() : 0,
                xpProgress != null ? xpProgress.getLevel() : 0,
                habit.getConstance(),
                habit.getCreatedAt() != null ? habit.getCreatedAt().toLocalDate() : null,
                habit.getUpdatedAt() != null ? habit.getUpdatedAt().toLocalDate() : null
        );
    }
}

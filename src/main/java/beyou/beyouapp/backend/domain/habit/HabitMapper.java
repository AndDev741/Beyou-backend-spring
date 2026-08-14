package beyou.beyouapp.backend.domain.habit;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.habit.dto.EditHabitDTO;
import beyou.beyouapp.backend.domain.checkday.CheckProgressCalculator;
import beyou.beyouapp.backend.domain.common.CheckProgress;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.habit.dto.HabitResponseDTO;
import beyou.beyouapp.backend.domain.routine.Routine;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.user.User;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
        // R2/R3 — the check scalars ship whole, and the lifetime counter finally travels
        // under its own name. Null-guarded the same way xpProgress is: Hibernate
        // materialises a null embeddable when every mapped column is null, so a habit row
        // written before V13 comes back with no CheckProgress at all.
        CheckProgress checkProgress = habit.getCheckProgress();
        // R15 — dormancy is a question about "recently", so it needs a today, and the only
        // correct today is the owner's. habit.getUser() is an eager @ManyToOne already
        // loaded by the list query, so this costs no statement; see
        // HabitFindAllByUserIdQueryCountTest.
        boolean streakDormant = CheckProgressCalculator.isDormant(
                checkProgress, UserDateResolver.today(habit.getUser()));

        Map<UUID, String> routines = Optional.ofNullable(habit.getHabitGroups())
            .orElse(List.of())
            .stream()
            .map(HabitGroup::getRoutineSection)
            .map(RoutineSection::getRoutine)
            .collect(Collectors.toMap(
                Routine::getId, 
                Routine::getName,
                (a, b) -> a
            ));

        return new HabitResponseDTO(
                habit.getId(),
                habit.getName(),
                habit.getDescription(),
                habit.getMotivationalPhrase(),
                habit.getIconId(),
                habit.getImportance(),
                habit.getDificulty(),
                // Copy eagerly while the session is open: the lazy collection must
                // not leak into the DTO — streaming serializes with no session.
                habit.getCategories() != null ? new ArrayList<>(habit.getCategories()) : List.of(),
                xpProgress != null ? xpProgress.getXp() : 0,
                xpProgress != null ? xpProgress.getActualLevelXp() : 0,
                xpProgress != null ? xpProgress.getNextLevelXp() : 0,
                xpProgress != null ? xpProgress.getLevel() : 0,
                checkProgress != null ? checkProgress.getCurrentStreak() : 0,
                checkProgress != null ? checkProgress.getBestStreak() : 0,
                checkProgress != null ? checkProgress.getTotalCheckIns() : 0,
                checkProgress != null ? checkProgress.getFirstCheckInDate() : null,
                streakDormant,
                habit.getCreatedAt() != null ? habit.getCreatedAt().toLocalDate() : null,
                habit.getUpdatedAt() != null ? habit.getUpdatedAt().toLocalDate() : null,
                routines
        );
    }
}

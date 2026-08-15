package beyou.beyouapp.backend.domain.common;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.xpday.XpDayOwnerType;
import beyou.beyouapp.backend.domain.xpday.XpDayRecorder;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@AllArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
@Slf4j
public class XpCalculatorService {

    private final XpByLevelRepository xpByLevelRepository;
    private final UserRepository userRepository;
    private final DiaryRoutineRepository diaryRoutineRepository;
    private final HabitRepository habitRepository;
    private final CategoryRepository categoryRepository;
    private final XpDayRecorder xpDayRecorder;
    private final GoalRepository goalRepository;

    // XP always flows through the User-explicit methods below: identity travels
    // with the data, never from SecurityContextHolder (a ThreadLocal that isn't
    // present on the agent's boundedElastic tool-execution thread).

    public void addXpToUserGoalAndCategoriesAndPersist(User user, Double newXp, Goal goal,
            List<Category> categories) {
        addUserXpAndPersist(user, newXp);
        addGoalXpAndPersist(newXp, goal);
        addCategoriesXpAndPersist(user, newXp, categories);
    }

    public void removeXpOfUserGoalAndCategoriesAndPersist(User user, Double newXp, Goal goal,
            List<Category> categories) {
        removeUserXpAndPersist(user, newXp);
        removeGoalXpAndPersist(goal);
        removeCategoriesXpAndPersist(user, newXp, categories);
    }

    public void addXpToUserRoutineHabitAndCategoriesAndPersist(User user, Double newXp, DiaryRoutine routine,
            Habit habit, List<Category> categories) {
        addUserXpAndPersist(user, newXp);
        addRoutineXpAndPersist(user, newXp, routine);
        addHabitXpAndPersist(user, newXp, habit);
        addCategoriesXpAndPersist(user, newXp, categories);
    }

    public void removeXpOfUserRoutineHabitAndCategoriesAndPersist(User user, Double xpToRemove, DiaryRoutine routine,
            Habit habit, List<Category> categories) {
        removeUserXpAndPersist(user, xpToRemove);
        removeRoutineXpAndPersist(user, xpToRemove, routine);
        removeHabitXpAndPersist(user, xpToRemove, habit);
        removeCategoriesXpAndPersist(user, xpToRemove, categories);
    }

    public void addXpToUserRoutineAndCategoriesAndPersist(User user, Double newXp, DiaryRoutine routine,
            List<Category> categories) {
        addUserXpAndPersist(user, newXp);
        addRoutineXpAndPersist(user, newXp, routine);
        addCategoriesXpAndPersist(user, newXp, categories);
    }

    public void removeXpOfUserRoutineAndCategoriesAndPersist(User user, Double xpToRemove, DiaryRoutine routine,
            List<Category> categories) {
        removeUserXpAndPersist(user, xpToRemove);
        removeRoutineXpAndPersist(user, xpToRemove, routine);
        removeCategoriesXpAndPersist(user, xpToRemove, categories);
    }

    public void addXpToUserAndRoutineOnly(User user, Double newXp, DiaryRoutine routine) {
        addUserXpAndPersist(user, newXp);
        addRoutineXpAndPersist(user, newXp, routine);
    }

    public void removeXpFromUserAndRoutineOnly(User user, Double xpToRemove, DiaryRoutine routine) {
        removeUserXpAndPersist(user, xpToRemove);
        removeRoutineXpAndPersist(user, xpToRemove, routine);
    }

    public void addXpToUserOnly(User user, Double newXp) {
        addUserXpAndPersist(user, newXp);
    }

    public void removeXpFromUserOnly(User user, Double xpToRemove) {
        removeUserXpAndPersist(user, xpToRemove);
    }

    private void addUserXpAndPersist(User user, Double newXp) {
        user.getXpProgress().addXp(newXp, level -> xpByLevelRepository.findByLevel(level));
        xpDayRecorder.record(user, XpDayOwnerType.USER, user.getId(), newXp);
        try {
            userRepository.save(user);
        } catch (Exception e) {
            log.error("ERROR ADDING XP TO USER -> {}", e.getMessage());
            throw e;
        }
    }

    private void addRoutineXpAndPersist(User user, Double newXp, DiaryRoutine routine) {
        routine.getXpProgress().addXp(
                newXp,
                level -> xpByLevelRepository.findByLevel(level));

        xpDayRecorder.record(user, XpDayOwnerType.ROUTINE, routine.getId(), newXp);

        try {
            diaryRoutineRepository.save(routine);
        } catch (Exception e) {
            log.error("ERROR ADDING XP TO ROUTINE -> {}", e.getMessage());
            throw e;
        }
    }

    private void addHabitXpAndPersist(User user, Double newXp, Habit habit) {
        habit.getXpProgress().addXp(
                newXp,
                level -> xpByLevelRepository.findByLevel(level));

        xpDayRecorder.record(user, XpDayOwnerType.HABIT, habit.getId(), newXp);

        try {
            habitRepository.save(habit);
        } catch (Exception e) {
            log.error("ERROR ADDING XP TO HABIT -> {}", e.getMessage());
            throw e;
        }
    }

    private void addCategoriesXpAndPersist(User user, Double newXp, List<Category> categories) {
        if (categories == null || categories.isEmpty()) {
            return;
        }

        categories.forEach(c -> c.getXpProgress().addXp(
                newXp,
                level -> xpByLevelRepository.findByLevel(level)));

        xpDayRecorder.recordAll(user, XpDayOwnerType.CATEGORY,
                categories.stream().map(Category::getId).toList(), newXp);

        try {
            categoryRepository.saveAll(categories);
        } catch (Exception e) {
            log.error("ERROR ADDING XP TO CATEGORIES -> {}", e.getMessage());
            throw e;
        }
    }

    private void addGoalXpAndPersist(Double newXp, Goal goal) {
        goal.setXpReward(newXp);

        try {
            goalRepository.save(goal);
        } catch (Exception e) {
            log.error("ERROR ADDING XP TO GOAL -> {}", e.getMessage());
            throw e;
        }
    }

    private void removeGoalXpAndPersist(Goal goal) {
        goal.setXpReward(0);

        try {
            goalRepository.save(goal);
        } catch (Exception e) {
            log.error("ERROR REMOVING XP TO GOAL -> {}", e.getMessage());
            throw e;
        }
    }

    private void removeUserXpAndPersist(User user, Double xpToRemove) {
        xpDayRecorder.record(user, XpDayOwnerType.USER, user.getId(), -xpToRemove);
        user.getXpProgress().removeXp(xpToRemove, level -> xpByLevelRepository.findByLevel(level));
        try {
            userRepository.save(user);
        } catch (Exception e) {
            log.error("ERROR REMOVING XP FROM USER -> {}", e.getMessage());
            throw e;
        }
    }

    private void removeRoutineXpAndPersist(User user, Double xpToRemove, DiaryRoutine routine) {
        xpDayRecorder.record(user, XpDayOwnerType.ROUTINE, routine.getId(), -xpToRemove);
        routine.getXpProgress().removeXp(
                xpToRemove,
                level -> xpByLevelRepository.findByLevel(level));

        try {
            diaryRoutineRepository.save(routine);
        } catch (Exception e) {
            log.error("ERROR REMOVING XP FROM ROUTINE -> {}", e.getMessage());
            throw e;
        }
    }

    private void removeHabitXpAndPersist(User user, Double xpToRemove, Habit habit) {
        xpDayRecorder.record(user, XpDayOwnerType.HABIT, habit.getId(), -xpToRemove);
        habit.getXpProgress().removeXp(
                xpToRemove,
                level -> xpByLevelRepository.findByLevel(level));

        try {
            habitRepository.save(habit);
        } catch (Exception e) {
            log.error("ERROR REMOVING XP FROM HABIT -> {}", e.getMessage());
            throw e;
        }
    }

    private void removeCategoriesXpAndPersist(User user, Double xpToRemove, List<Category> categories) {
        if (categories == null || categories.isEmpty()) {
            return;
        }

        categories.forEach(c -> c.getXpProgress().removeXp(
                xpToRemove,
                level -> xpByLevelRepository.findByLevel(level)));

        // Negative: the day gives the XP back rather than remembering a high-water mark.
        xpDayRecorder.recordAll(user, XpDayOwnerType.CATEGORY,
                categories.stream().map(Category::getId).toList(), -xpToRemove);

        try {
            categoryRepository.saveAll(categories);
        } catch (Exception e) {
            log.error("ERROR REMOVING XP FROM CATEGORIES -> {}", e.getMessage());
            throw e;
        }
    }
}

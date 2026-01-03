package beyou.beyouapp.backend.domain.common;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.security.AuthenticatedUser;
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
    private final AuthenticatedUser authenticatedUser;
    private final UserRepository userRepository;
    private final DiaryRoutineRepository diaryRoutineRepository;
    private final HabitRepository habitRepository;
    private final CategoryRepository categoryRepository;
    private final GoalRepository goalRepository;

    public void addXpToUserRoutineHabitAndCategoriesAndPersist(Double newXp, DiaryRoutine routine, Habit habit,
            List<Category> categories) {
        addUserXpAndPersist(newXp);
        addRoutineXpAndPersist(newXp, routine);
        addHabitXpAndPersist(newXp, habit);
        addCategoriesXpAndPersist(newXp, categories);
    }

    public void addXpToUserRoutineAndCategoriesAndPersist(Double newXp, DiaryRoutine routine,
            List<Category> categories) {
        addUserXpAndPersist(newXp);
        addRoutineXpAndPersist(newXp, routine);
        addCategoriesXpAndPersist(newXp, categories);
    }

    public void addXpToUserGoalAndCategoriesAndPersist(Double newXp, Goal goal,
            List<Category> categories) {
        addUserXpAndPersist(newXp);
        addGoalXpAndPersist(newXp, goal);
        addCategoriesXpAndPersist(newXp, categories);
    }

    public void removeXpOfUserRoutineHabitAndCategoriesAndPersist(Double xpToRemove, DiaryRoutine routine, Habit habit,
            List<Category> categories) {
        removeUserXpAndPersist(xpToRemove);
        removeRoutineXpAndPersist(xpToRemove, routine);
        removeHabitXpAndPersist(xpToRemove, habit);
        removeCategoriesXpAndPersist(xpToRemove, categories);
    }

    public void removeXpOfUserRoutineAndCategoriesAndPersist(Double xpToRemove, DiaryRoutine routine,
            List<Category> categories) {
        removeUserXpAndPersist(xpToRemove);
        removeRoutineXpAndPersist(xpToRemove, routine);
        removeCategoriesXpAndPersist(xpToRemove, categories);
    }

    public void removeXpOfUserGoalAndCategoriesAndPersist(Double newXp, Goal goal,
            List<Category> categories) {
        removeUserXpAndPersist(newXp);
        removeGoalXpAndPersist(goal);
        removeCategoriesXpAndPersist(newXp, categories);
    }
    

    private void addUserXpAndPersist(Double newXp) {
        User user = authenticatedUser.getAuthenticatedUser();
        user.getXpProgress().addXp(
                newXp,
                level -> xpByLevelRepository.findByLevel(level));

        try {
            userRepository.save(user);
        } catch (Exception e) {
            log.error("ERROR ADDING XP TO USER -> {}", e.getMessage());
            throw e;
        }
    }

    private void addRoutineXpAndPersist(Double newXp, DiaryRoutine routine) {
        routine.getXpProgress().addXp(
                newXp,
                level -> xpByLevelRepository.findByLevel(level));

        try {
            diaryRoutineRepository.save(routine);
        } catch (Exception e) {
            log.error("ERROR ADDING XP TO ROUTINE -> {}", e.getMessage());
            throw e;
        }
    }

    private void addHabitXpAndPersist(Double newXp, Habit habit) {
        habit.getXpProgress().addXp(
                newXp,
                level -> xpByLevelRepository.findByLevel(level));

        try {
            habitRepository.save(habit);
        } catch (Exception e) {
            log.error("ERROR ADDING XP TO HABIT -> {}", e.getMessage());
            throw e;
        }
    }

    private void addCategoriesXpAndPersist(Double newXp, List<Category> categories) {
        if (categories == null || categories.isEmpty()) {
            return;
        }

        categories.forEach(c -> c.getXpProgress().addXp(
                newXp,
                level -> xpByLevelRepository.findByLevel(level)));

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

    private void removeUserXpAndPersist(Double xpToRemove) {
        User user = authenticatedUser.getAuthenticatedUser();
        user.getXpProgress().removeXp(
                xpToRemove,
                level -> xpByLevelRepository.findByLevel(level));

        try {
            userRepository.save(user);
        } catch (Exception e) {
            log.error("ERROR REMOVING XP FROM USER -> {}", e.getMessage());
            throw e;
        }
    }

    private void removeRoutineXpAndPersist(Double xpToRemove, DiaryRoutine routine) {
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

    private void removeHabitXpAndPersist(Double xpToRemove, Habit habit) {
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

    private void removeCategoriesXpAndPersist(Double xpToRemove, List<Category> categories) {
        if (categories == null || categories.isEmpty()) {
            return;
        }

        categories.forEach(c -> c.getXpProgress().removeXp(
                xpToRemove,
                level -> xpByLevelRepository.findByLevel(level)));

        try {
            categoryRepository.saveAll(categories);
        } catch (Exception e) {
            log.error("ERROR REMOVING XP FROM CATEGORIES -> {}", e.getMessage());
            throw e;
        }
    }
}

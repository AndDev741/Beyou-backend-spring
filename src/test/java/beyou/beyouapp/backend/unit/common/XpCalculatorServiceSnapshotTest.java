package beyou.beyouapp.backend.unit.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class XpCalculatorServiceSnapshotTest {

    @Mock
    private XpByLevelRepository xpByLevelRepository;

    @Mock
    private AuthenticatedUser authenticatedUser;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DiaryRoutineRepository diaryRoutineRepository;

    @Mock
    private HabitRepository habitRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private GoalRepository goalRepository;

    @InjectMocks
    private XpCalculatorService xpCalculatorService;

    private User user;
    private DiaryRoutine routine;
    private Habit habit;
    private List<Category> categories;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        seedXp(user.getXpProgress(), 0);

        routine = new DiaryRoutine();
        routine.setId(UUID.randomUUID());
        seedXp(routine.getXpProgress(), 0);

        habit = new Habit();
        habit.setId(UUID.randomUUID());
        seedXp(habit.getXpProgress(), 0);

        Category category = new Category();
        category.setId(UUID.randomUUID());
        seedXp(category.getXpProgress(), 0);
        categories = new ArrayList<>(List.of(category));
    }

    // --- Test 1: addXpToUserRoutineHabitAndCategoriesAndPersist(User, ...) does NOT call authenticatedUser ---

    @Test
    void snapshotAddXpToUserRoutineHabitAndCategories_doesNotUseSecurityContext() {
        xpCalculatorService.addXpToUserRoutineHabitAndCategoriesAndPersist(user, 50.0, routine, habit, categories);

        verifyNoInteractions(authenticatedUser);

        assertEquals(50.0, user.getXpProgress().getXp());
        assertEquals(50.0, routine.getXpProgress().getXp());
        assertEquals(50.0, habit.getXpProgress().getXp());
        assertEquals(50.0, categories.get(0).getXpProgress().getXp());

        verify(userRepository).save(user);
        verify(diaryRoutineRepository).save(routine);
        verify(habitRepository).save(habit);
        verify(categoryRepository).saveAll(categories);
    }

    @Test
    void snapshotRemoveXpOfUserRoutineHabitAndCategories_doesNotUseSecurityContext() {
        seedXp(user.getXpProgress(), 80.0);
        seedXp(routine.getXpProgress(), 80.0);
        seedXp(habit.getXpProgress(), 80.0);
        seedXp(categories.get(0).getXpProgress(), 80.0);

        xpCalculatorService.removeXpOfUserRoutineHabitAndCategoriesAndPersist(user, 30.0, routine, habit, categories);

        verifyNoInteractions(authenticatedUser);

        assertEquals(50.0, user.getXpProgress().getXp());
        assertEquals(50.0, routine.getXpProgress().getXp());
        assertEquals(50.0, habit.getXpProgress().getXp());
        assertEquals(50.0, categories.get(0).getXpProgress().getXp());

        verify(userRepository).save(user);
        verify(diaryRoutineRepository).save(routine);
        verify(habitRepository).save(habit);
        verify(categoryRepository).saveAll(categories);
    }

    @Test
    void snapshotAddXpToUserRoutineAndCategories_doesNotUseSecurityContext() {
        xpCalculatorService.addXpToUserRoutineAndCategoriesAndPersist(user, 40.0, routine, categories);

        verifyNoInteractions(authenticatedUser);

        assertEquals(40.0, user.getXpProgress().getXp());
        assertEquals(40.0, routine.getXpProgress().getXp());
        assertEquals(40.0, categories.get(0).getXpProgress().getXp());

        verify(userRepository).save(user);
        verify(diaryRoutineRepository).save(routine);
        verify(categoryRepository).saveAll(categories);
        verify(habitRepository, never()).save(any(Habit.class));
    }

    @Test
    void snapshotRemoveXpOfUserRoutineAndCategories_doesNotUseSecurityContext() {
        seedXp(user.getXpProgress(), 60.0);
        seedXp(routine.getXpProgress(), 60.0);
        seedXp(categories.get(0).getXpProgress(), 60.0);

        xpCalculatorService.removeXpOfUserRoutineAndCategoriesAndPersist(user, 20.0, routine, categories);

        verifyNoInteractions(authenticatedUser);

        assertEquals(40.0, user.getXpProgress().getXp());
        assertEquals(40.0, routine.getXpProgress().getXp());
        assertEquals(40.0, categories.get(0).getXpProgress().getXp());

        verify(userRepository).save(user);
        verify(diaryRoutineRepository).save(routine);
        verify(categoryRepository).saveAll(categories);
    }

    // --- Test 2: addXpToUserAndRoutineOnly only saves user and routine ---

    @Test
    void snapshotAddXpToUserAndRoutineOnly_onlySavesUserAndRoutine() {
        xpCalculatorService.addXpToUserAndRoutineOnly(user, 25.0, routine);

        verifyNoInteractions(authenticatedUser);

        assertEquals(25.0, user.getXpProgress().getXp());
        assertEquals(25.0, routine.getXpProgress().getXp());

        verify(userRepository).save(user);
        verify(diaryRoutineRepository).save(routine);
        verify(habitRepository, never()).save(any(Habit.class));
        verify(categoryRepository, never()).saveAll(any());
    }

    // --- Test 3: removeXpFromUserAndRoutineOnly only saves user and routine ---

    @Test
    void snapshotRemoveXpFromUserAndRoutineOnly_onlySavesUserAndRoutine() {
        seedXp(user.getXpProgress(), 100.0);
        seedXp(routine.getXpProgress(), 100.0);

        xpCalculatorService.removeXpFromUserAndRoutineOnly(user, 35.0, routine);

        verifyNoInteractions(authenticatedUser);

        assertEquals(65.0, user.getXpProgress().getXp());
        assertEquals(65.0, routine.getXpProgress().getXp());

        verify(userRepository).save(user);
        verify(diaryRoutineRepository).save(routine);
        verify(habitRepository, never()).save(any(Habit.class));
        verify(categoryRepository, never()).saveAll(any());
    }

    private void seedXp(XpProgress xpProgress, double xp) {
        xpProgress.setLevel(1);
        xpProgress.setXp(xp);
        xpProgress.setActualLevelXp(0);
        xpProgress.setNextLevelXp(1000);
    }
}

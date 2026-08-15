package beyou.beyouapp.backend.unit.habit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import beyou.beyouapp.backend.domain.xpday.XpDayRecorder;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitMapper;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.habit.dto.EditHabitDTO;
import beyou.beyouapp.backend.domain.habit.dto.HabitResponseDTO;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.habit.HabitNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
public class HabitServiceUnitTest {
    @Mock
    private HabitRepository habitRepository;

    /** The XP history is a chart; nothing in these tests asserts on it. */
    @Mock
    private XpDayRecorder xpDayRecorder;

    @Mock
    private UserRepository userRepository;

    @Mock
    private XpByLevelRepository xpByLevelRepository;

    @Mock
    private CategoryService categoryService;

    @Mock
    private DiaryRoutineRepository diaryRoutineRepository;

    @Mock
    private UserCacheEvictService userCacheEvictService;

    @Mock
    private EntityCheckDayRepository entityCheckDayRepository;

    private HabitMapper habitMapper = new HabitMapper();

    private HabitService habitService;

    Habit habit = new Habit();
    UUID habitId = UUID.randomUUID();
    User user = new User();
    UUID userId = UUID.randomUUID();
    Category newCategory = new Category();
    List<UUID> categories = new ArrayList<>(List.of(UUID.randomUUID()));

    @BeforeEach
    public void setup(){
        user.setId(userId);
        habit.setId(habitId);
        habit.setUser(user);
        habit.setName("Test");
        habit.setImportance(0);
        habit.setDificulty(1);

        habitService = new HabitService(habitRepository, xpDayRecorder, userRepository, xpByLevelRepository, categoryService, habitMapper, diaryRoutineRepository, userCacheEvictService, entityCheckDayRepository);
    }

    @Test
    public void shouldGetHabitSuccessfully(){  
        when(habitRepository.findById(habitId)).thenReturn(Optional.of(habit));

        Habit testHabit = habitService.getHabit(habitId);

        assertEquals(habit, testHabit);
        assertEquals(habit.getName(), "Test");
    }

    @Test
    public void shouldGetAllHabitsSuccessfully(){
        ArrayList<Habit> habits = new ArrayList<>(List.of(habit));

        when(habitRepository.findAllByUserId(userId)).thenReturn(habits);

        List<HabitResponseDTO> assertResponse = habitService.getHabits(userId);

        assertEquals(1, assertResponse.size());
    }

    @Test
    public void shouldCreateHabitSuccessfully(){
        CreateHabitDTO createHabitDTO = new CreateHabitDTO(
        "name", "", "", "", 2, 2,
        categories, ExperienceLevel.BEGINNER);

        XpByLevel xpByLevel = new XpByLevel(0, 0);
        ResponseEntity<Map<String, String>> response = ResponseEntity.ok().body(Map.of("success", "Habit saved successfully"));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(xpByLevelRepository.findByLevel(0)).thenReturn(xpByLevel);
        when(xpByLevelRepository.findByLevel(0 + 1)).thenReturn(xpByLevel);
        when(categoryService.getCategory(categories.get(0), userId)).thenReturn(newCategory);

        ResponseEntity<Map<String, String>> assertResponse = habitService.createHabit(createHabitDTO, userId);

        assertEquals(response.getBody(), assertResponse.getBody());
        assertEquals(response.getStatusCode(), assertResponse.getStatusCode());
    }

    @Test
    public void shouldEditHabitSuccessfully(){
        EditHabitDTO editHabitDTO = new EditHabitDTO(habitId, "editedName", 
        "", "", "", 0, 0, categories);
        ResponseEntity<Map<String, String>> response = ResponseEntity.ok().body(Map.of("success", "Habit edited successfully"));

        when(habitRepository.findById(habitId)).thenReturn(Optional.of(habit));
        when(categoryService.getCategory(categories.get(0), userId)).thenReturn(newCategory);

        ResponseEntity<Map<String, String>> assertResponse = habitService.editHabit(editHabitDTO, userId);

        assertEquals(response.getBody(), assertResponse.getBody());
        assertEquals(response.getStatusCode(), assertResponse.getStatusCode());
        
        
    }

    @Test
    public void shouldDeleteHabitSuccessfully(){
        ResponseEntity<Map<String, String>> response = ResponseEntity.ok().body(Map.of("success", "habit deleted successfully"));

        when(habitRepository.findById(habitId)).thenReturn(Optional.of(habit));

        ResponseEntity<Map<String, String>> assertResponse = habitService.deleteHabit(habitId, userId);

        assertEquals(response.getBody(), assertResponse.getBody());
        assertEquals(response.getStatusCode(), assertResponse.getStatusCode());
    }

    /**
     * R8/KTD24 — the habit's day history goes with the habit. Asserted on the deletion path
     * itself rather than only end to end, because the call is easy to drop in a refactor and
     * nothing else in this class would notice.
     */
    @Test
    public void shouldDeleteTheHabitsCheckDayHistoryWhenDeletingTheHabit(){
        when(habitRepository.findById(habitId)).thenReturn(Optional.of(habit));

        habitService.deleteHabit(habitId, userId);

        verify(entityCheckDayRepository).deleteAllByOwner(CheckDayOwnerType.HABIT, habitId);
    }

    /**
     * The other half of the asymmetry, from the only angle a unit test can see it: a habit
     * still held by a routine is refused, so its history is never touched on the way out.
     */
    @Test
    public void shouldNotTouchTheHistoryWhenTheDeleteIsRefused(){
        DiaryRoutine routine = new DiaryRoutine();
        RoutineSection section = new RoutineSection();
        HabitGroup group = new HabitGroup();
        group.setHabit(habit);
        section.setHabitGroups(new ArrayList<>(List.of(group)));
        routine.setRoutineSections(new ArrayList<>(List.of(section)));

        when(habitRepository.findById(habitId)).thenReturn(Optional.of(habit));
        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of(routine));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> habitService.deleteHabit(habitId, userId));

        assertEquals(ErrorKey.HABIT_IN_ROUTINE, exception.getErrorKey());
        verifyNoInteractions(entityCheckDayRepository);
    }

    //Exception

    @Test
    public void shouldThrowHabitNotFound(){
        Exception assertException = assertThrows(HabitNotFound.class, () -> {
            habitService.getHabit(UUID.randomUUID());
        });

        assertEquals("Habit not found", assertException.getMessage());
    }
}

package beyou.beyouapp.backend.integration.routine;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.HabitGroupDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Removing a section on update must delete it, not leave it orphaned. */
class DiaryRoutineUpdateOrphanIT extends AbstractIntegrationTest {

    @Autowired private DiaryRoutineService diaryRoutineService;
    @Autowired private DiaryRoutineRepository diaryRoutineRepository;
    @Autowired private HabitRepository habitRepository;
    @Autowired private CategoryService categoryService;
    @Autowired private HabitService habitService;
    @Autowired private UserRepository userRepository;
    @Autowired private XpByLevelRepository xpByLevelRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private User user;
    private UUID habitId;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setName("Update Orphan IT User");
        user.setEmail("update-orphan-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user = userRepository.saveAndFlush(user);
        if (xpByLevelRepository.findByLevel(0) == null) xpByLevelRepository.save(new XpByLevel(0, 0));
        if (xpByLevelRepository.findByLevel(1) == null) xpByLevelRepository.save(new XpByLevel(1, 100));

        Category category = categoryService.createCategoryEntity(
                new CategoryRequestDTO(null, "Health", "ic", null, ExperienceLevel.BEGINNER), user);
        habitId = habitService.createHabitEntity(
                new CreateHabitDTO(null, "Read", null, null, "ic", 3, 3, List.of(category.getId()),
                        ExperienceLevel.BEGINNER),
                user.getId()).getId();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void updateRemovingPopulatedSectionDeletesItFromDb() {
        RoutineSectionRequestDTO sectionA = new RoutineSectionRequestDTO(
                null, "A", "ic", LocalTime.of(6, 0), LocalTime.of(7, 0),
                List.of(),
                List.of(new HabitGroupDTO(null, habitId, LocalTime.of(6, 0), LocalTime.of(6, 30), null)),
                false);
        RoutineSectionRequestDTO sectionB = new RoutineSectionRequestDTO(
                null, "B", "ic", LocalTime.of(8, 0), LocalTime.of(9, 0), List.of(), List.of(), false);

        DiaryRoutineResponseDTO created = diaryRoutineService.createDiaryRoutine(
                new DiaryRoutineRequestDTO(null, "R", "", List.of(sectionA, sectionB)), user);
        assertEquals(2, created.routineSections().size());
        UUID routineId = created.id();
        UUID sectionBId = created.routineSections().stream()
                .filter(s -> s.name().equals("B")).findFirst().orElseThrow().id();

        RoutineSectionRequestDTO keepB = new RoutineSectionRequestDTO(
                sectionBId, "B", "ic", LocalTime.of(8, 0), LocalTime.of(9, 0), List.of(), List.of(), false);
        diaryRoutineService.updateDiaryRoutine(routineId,
                new DiaryRoutineRequestDTO(null, "R", "", List.of(keepB)), user.getId());

        transactionTemplate.executeWithoutResult(tx -> {
            DiaryRoutine routine = diaryRoutineRepository.findById(routineId).orElseThrow();
            assertEquals(1, routine.getRoutineSections().size(),
                    "removed section must be deleted, not orphaned");
            assertEquals("B", routine.getRoutineSections().get(0).getName());
        });

        assertTrue(habitRepository.findById(habitId).isPresent(),
                "habit survives — only its routine membership was deleted");
    }
}

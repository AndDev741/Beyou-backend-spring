package beyou.beyouapp.backend.integration.ai;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.ai.AiRoutineConfirmService;
import beyou.beyouapp.backend.domain.ai.dto.DraftHabitItemDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewCategoryDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewHabitDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewTaskDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftSectionDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftTaskItemDTO;
import beyou.beyouapp.backend.domain.ai.dto.RoutineDraftDTO;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiRoutineConfirmServiceIT extends AbstractIntegrationTest {

    @Autowired private AiRoutineConfirmService confirmService;
    @Autowired private UserRepository userRepository;
    @Autowired private HabitRepository habitRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private DiaryRoutineRepository diaryRoutineRepository;
    @Autowired private XpByLevelRepository xpByLevelRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setName("AI Confirm IT User");
        user.setEmail("ai-confirm-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user = userRepository.saveAndFlush(user);
        // Flyway's R__seed_xp_by_level.sql seeds all levels, but make 0/1 explicit for safety:
        if (xpByLevelRepository.findByLevel(0) == null) xpByLevelRepository.save(new XpByLevel(0, 0));
        if (xpByLevelRepository.findByLevel(1) == null) xpByLevelRepository.save(new XpByLevel(1, 100));
    }

    private RoutineDraftDTO fullDraft() {
        return new RoutineDraftDTO(
                "AI Morning Routine", "ri:md/MdWbSunny",
                List.of(new DraftNewCategoryDTO("new-1", "Wellness", "ri:md/MdSpa", "ai test")),
                List.of(new DraftSectionDTO("Morning", "ri:md/MdWbSunny", "06:00", "09:00",
                        List.of(new DraftHabitItemDTO(null,
                                new DraftNewHabitDTO("Drink water", null, null, "ri:md/MdLocalDrink",
                                        3, 1, List.of("new-1")),
                                "06:00", "06:10")),
                        List.of(new DraftTaskItemDTO(null,
                                new DraftNewTaskDTO("Prepare breakfast", null, "ri:md/MdBreakfastDining",
                                        2, 1, List.of("new-1"), false),
                                "06:20", "06:40")))),
                Set.of(WeekDay.Monday));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void confirmCreatesCategoryHabitTaskRoutineAndScheduleAtomically() {
        DiaryRoutineResponseDTO created = confirmService.confirm(fullDraft(), user);

        assertNotNull(created.id());
        assertEquals(1, habitRepository.findAllByUserId(user.getId()).size());
        assertEquals(1, taskRepository.findAllByUserId(user.getId()).orElse(new ArrayList<>()).size());
        assertEquals(1, categoryRepository.findAllByUserId(user.getId()).orElse(new ArrayList<>()).size());

        // entity-graph assertions need an open session (lazy collections)
        transactionTemplate.executeWithoutResult(tx -> {
            DiaryRoutine routine = diaryRoutineRepository.findById(created.id()).orElseThrow();
            assertEquals(1, routine.getRoutineSections().size());
            assertNotNull(routine.getSchedule());
            assertTrue(routine.getSchedule().getDays().contains(WeekDay.Monday));

            // new habit is linked to the new category
            Habit habit = habitRepository.findAllByUserId(user.getId()).get(0);
            assertEquals("Wellness", habit.getCategories().get(0).getName());
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void confirmReusesExistingHabitWithoutDuplicating() {
        DiaryRoutineResponseDTO first = confirmService.confirm(fullDraft(), user);
        assertNotNull(first.id());
        UUID existingHabitId = habitRepository.findAllByUserId(user.getId()).get(0).getId();

        RoutineDraftDTO reuseDraft = new RoutineDraftDTO(
                "AI Evening Routine", "ri:md/MdNightlight",
                List.of(),
                List.of(new DraftSectionDTO("Evening", "ri:md/MdNightlight", "20:00", "22:00",
                        List.of(new DraftHabitItemDTO(existingHabitId, null, "20:00", "20:10")),
                        List.of())),
                null);

        confirmService.confirm(reuseDraft, user);

        assertEquals(1, habitRepository.findAllByUserId(user.getId()).size()); // no duplicate
        assertEquals(2, diaryRoutineRepository.findAllByUserId(user.getId()).size());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void confirmWithRoutineIdReplacesStructureOfExistingRoutine() {
        DiaryRoutineResponseDTO created = confirmService.confirm(fullDraft(), user);
        UUID existingHabitId = habitRepository.findAllByUserId(user.getId()).get(0).getId();

        RoutineDraftDTO updateDraft = new RoutineDraftDTO(
                "AI Morning Routine v2", "ri:md/MdNightlight",
                List.of(),
                List.of(
                        new DraftSectionDTO("Early Morning", "ri:md/MdAlarm", "05:30", "07:00",
                                List.of(new DraftHabitItemDTO(existingHabitId, null, "05:30", "05:40")),
                                List.of()),
                        new DraftSectionDTO("Evening", "ri:md/MdNightlight", "20:00", "22:00",
                                List.of(new DraftHabitItemDTO(null,
                                        new DraftNewHabitDTO("Read before bed", null, null, "ri:md/MdMenuBook",
                                                3, 2, List.of()),
                                        "21:00", "21:30")),
                                List.of())),
                null);

        DiaryRoutineResponseDTO updated = confirmService.confirm(updateDraft, user, created.id());

        assertEquals(created.id(), updated.id()); // same routine, structure replaced
        assertEquals("AI Morning Routine v2", updated.name());
        assertEquals(2, updated.routineSections().size());
        assertEquals(1, diaryRoutineRepository.findAllByUserId(user.getId()).size()); // no new routine
        assertEquals(2, habitRepository.findAllByUserId(user.getId()).size()); // 1 reused + 1 new

        transactionTemplate.executeWithoutResult(tx -> {
            DiaryRoutine routine = diaryRoutineRepository.findById(created.id()).orElseThrow();
            assertNotNull(routine.getSchedule()); // schedule untouched in update mode
        });
    }
}

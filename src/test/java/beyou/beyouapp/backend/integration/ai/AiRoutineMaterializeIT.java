package beyou.beyouapp.backend.integration.ai;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.ai.AiRoutineConfirmService;
import beyou.beyouapp.backend.domain.ai.dto.DraftHabitItemDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewCategoryDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewHabitDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewTaskDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftSectionDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftTaskItemDTO;
import beyou.beyouapp.backend.domain.ai.dto.MaterializeRoutineResponseDTO;
import beyou.beyouapp.backend.domain.ai.dto.RoutineDraftDTO;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiRoutineMaterializeIT extends AbstractIntegrationTest {

    @Autowired private AiRoutineConfirmService confirmService;
    @Autowired private UserRepository userRepository;
    @Autowired private HabitRepository habitRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private DiaryRoutineRepository diaryRoutineRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setName("AI Materialize IT User");
        user.setEmail("ai-materialize-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user = userRepository.saveAndFlush(user);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void materializeCreatesNewEntitiesButNotTheRoutineAndReturnsRefs() {
        RoutineDraftDTO draft = new RoutineDraftDTO(
                "AI Morning Routine", "ri:md/MdWbSunny",
                List.of(new DraftNewCategoryDTO("new-1", "Wellness", "ri:md/MdSpa", null)),
                List.of(new DraftSectionDTO("Morning", "ri:md/MdWbSunny", "06:00", "09:00",
                        List.of(new DraftHabitItemDTO(null,
                                new DraftNewHabitDTO("Drink water", null, null, "ri:md/MdLocalDrink",
                                        3, 1, List.of("new-1")),
                                "06:00", "06:10")),
                        List.of(new DraftTaskItemDTO(null,
                                new DraftNewTaskDTO("Prepare breakfast", null, "ri:md/MdBreakfastDining",
                                        2, 1, List.of("new-1"), false),
                                "06:20", "06:40")))),
                null);

        MaterializeRoutineResponseDTO result = confirmService.materialize(draft, user);

        // new entities persisted
        assertEquals(1, habitRepository.findAllByUserId(user.getId()).size());
        assertEquals(1, taskRepository.findAllByUserId(user.getId()).orElse(new ArrayList<>()).size());
        assertEquals(1, categoryRepository.findAllByUserId(user.getId()).orElse(new ArrayList<>()).size());
        // but NO routine
        assertEquals(0, diaryRoutineRepository.findAllByUserId(user.getId()).size());

        // refs + new-id lists for the UI
        assertEquals(1, result.newHabitIds().size());
        assertEquals(1, result.newTaskIds().size());
        assertEquals(1, result.newCategoryIds().size());
        assertEquals(1, result.sections().size());
        UUID habitId = result.sections().get(0).habitGroup().get(0).habitId();
        assertEquals(result.newHabitIds().get(0), habitId);
        assertEquals("06:00", result.sections().get(0).habitGroup().get(0).startTime());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void materializeReusesExistingHabitWithoutMarkingItNew() {
        // first materialize creates the habit
        MaterializeRoutineResponseDTO first = confirmService.materialize(
                new RoutineDraftDTO("R1", null, List.of(),
                        List.of(new DraftSectionDTO("Morning", null, "06:00", "09:00",
                                List.of(new DraftHabitItemDTO(null,
                                        new DraftNewHabitDTO("Drink water", null, null, "ri:md/MdLocalDrink",
                                                3, 1, List.of()),
                                        "06:00", "06:10")),
                                List.of())),
                        null),
                user);
        UUID existingHabitId = first.newHabitIds().get(0);

        // second materialize references it by id
        MaterializeRoutineResponseDTO second = confirmService.materialize(
                new RoutineDraftDTO("R2", null, List.of(),
                        List.of(new DraftSectionDTO("Evening", null, "20:00", "22:00",
                                List.of(new DraftHabitItemDTO(existingHabitId, null, "20:00", "20:10")),
                                List.of())),
                        null),
                user);

        assertEquals(1, habitRepository.findAllByUserId(user.getId()).size()); // no duplicate
        assertEquals(0, second.newHabitIds().size()); // reused, not new
        assertEquals(existingHabitId, second.sections().get(0).habitGroup().get(0).habitId());
    }
}

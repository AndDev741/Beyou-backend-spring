package beyou.beyouapp.backend.unit.ai;

import beyou.beyouapp.backend.domain.ai.AiDraftValidator;
import beyou.beyouapp.backend.domain.ai.AiIconCatalog;
import beyou.beyouapp.backend.domain.ai.dto.DraftHabitItemDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewHabitDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewTaskDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftSectionDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftTaskItemDTO;
import beyou.beyouapp.backend.domain.ai.dto.RoutineDraftDTO;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AiDraftValidatorTest {

    @Mock private HabitRepository habitRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private CategoryRepository categoryRepository;

    private AiDraftValidator validator;
    private UUID userId;
    private UUID existingHabitId;
    private UUID existingCategoryId;

    @BeforeEach
    void setUp() {
        validator = new AiDraftValidator(habitRepository, taskRepository, categoryRepository);
        userId = UUID.randomUUID();
        existingHabitId = UUID.randomUUID();
        existingCategoryId = UUID.randomUUID();

        Habit habit = new Habit();
        habit.setId(existingHabitId);
        habit.setName("Read 30min");

        Category category = new Category();
        category.setId(existingCategoryId);
        category.setName("Health");

        lenient().when(habitRepository.findAllByUserId(userId)).thenReturn(new ArrayList<>(List.of(habit)));
        lenient().when(taskRepository.findAllByUserId(userId)).thenReturn(Optional.of(new ArrayList<>()));
        lenient().when(categoryRepository.findAllByUserId(userId)).thenReturn(Optional.of(new ArrayList<>(List.of(category))));
    }

    private DraftSectionDTO section(List<DraftHabitItemDTO> habits, List<DraftTaskItemDTO> tasks) {
        return new DraftSectionDTO("Morning", "ri:md/MdWbSunny", "06:00", "09:00", habits, tasks);
    }

    private RoutineDraftDTO draftWith(DraftSectionDTO... sections) {
        return new RoutineDraftDTO("My Routine", "ri:md/MdStar", List.of(), List.of(sections), null);
    }

    @Test
    void rejectsDraftWithoutSections() {
        RoutineDraftDTO draft = new RoutineDraftDTO("My Routine", null, List.of(), List.of(), null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateAndSanitize(draft, userId, ErrorKey.AI_RESPONSE_INVALID));
        assertEquals(ErrorKey.AI_RESPONSE_INVALID, ex.getErrorKey());
    }

    @Test
    void rejectsForeignHabitReference() {
        DraftHabitItemDTO foreignRef = new DraftHabitItemDTO(UUID.randomUUID(), null, "06:00", "06:30");
        RoutineDraftDTO draft = draftWith(section(List.of(foreignRef), List.of()));
        assertThrows(BusinessException.class,
                () -> validator.validateAndSanitize(draft, userId, ErrorKey.AI_DRAFT_INVALID));
    }

    @Test
    void prefersOwnedRefWhenItemHasBothRefAndNew() {
        DraftHabitItemDTO both = new DraftHabitItemDTO(existingHabitId,
                new DraftNewHabitDTO("X habit", null, null, null, 3, 3, List.of()), "06:00", "06:30");
        RoutineDraftDTO draft = draftWith(section(List.of(both), List.of()));

        RoutineDraftDTO result = validator.validateAndSanitize(draft, userId, ErrorKey.AI_RESPONSE_INVALID);

        DraftHabitItemDTO sanitized = result.sections().get(0).habits().get(0);
        assertEquals(existingHabitId, sanitized.existingHabitId());
        assertNull(sanitized.newHabit());
    }

    @Test
    void fallsBackToNewItemWhenBothSetButRefNotOwned() {
        DraftHabitItemDTO both = new DraftHabitItemDTO(UUID.randomUUID(),
                new DraftNewHabitDTO("Fresh habit", null, null, null, 3, 3, List.of()), "06:00", "06:30");
        RoutineDraftDTO draft = draftWith(section(List.of(both), List.of()));

        RoutineDraftDTO result = validator.validateAndSanitize(draft, userId, ErrorKey.AI_RESPONSE_INVALID);

        DraftHabitItemDTO sanitized = result.sections().get(0).habits().get(0);
        assertNull(sanitized.existingHabitId());
        assertEquals("Fresh habit", sanitized.newHabit().name());
    }

    @Test
    void dropsItemWithNeitherRefNorNew() {
        DraftHabitItemDTO husk = new DraftHabitItemDTO(null, null, "06:00", "06:30");
        DraftHabitItemDTO valid = new DraftHabitItemDTO(existingHabitId, null, "07:00", "07:30");
        RoutineDraftDTO draft = draftWith(section(List.of(husk, valid), List.of()));

        RoutineDraftDTO result = validator.validateAndSanitize(draft, userId, ErrorKey.AI_RESPONSE_INVALID);

        assertEquals(1, result.sections().get(0).habits().size());
        assertEquals(existingHabitId, result.sections().get(0).habits().get(0).existingHabitId());
    }

    @Test
    void rejectsUnknownCategoryRef() {
        DraftHabitItemDTO item = new DraftHabitItemDTO(null,
                new DraftNewHabitDTO("New habit", null, null, null, 3, 3, List.of("new-99")),
                "06:00", "06:30");
        RoutineDraftDTO draft = draftWith(section(List.of(item), List.of()));
        assertThrows(BusinessException.class,
                () -> validator.validateAndSanitize(draft, userId, ErrorKey.AI_RESPONSE_INVALID));
    }

    @Test
    void rejectsItemTimeOutsideSection() {
        DraftHabitItemDTO outOfBounds = new DraftHabitItemDTO(existingHabitId, null, "11:00", "11:30");
        RoutineDraftDTO draft = draftWith(section(List.of(outOfBounds), List.of()));
        assertThrows(BusinessException.class,
                () -> validator.validateAndSanitize(draft, userId, ErrorKey.AI_RESPONSE_INVALID));
    }

    @Test
    void fallsBackToDefaultIconAndClampsLevels() {
        DraftHabitItemDTO item = new DraftHabitItemDTO(null,
                new DraftNewHabitDTO("New habit", null, null, "ri:md/MdNotReal", 9, 0,
                        List.of(existingCategoryId.toString())),
                "06:00", "06:30");
        RoutineDraftDTO draft = draftWith(section(List.of(item), List.of()));

        RoutineDraftDTO result = validator.validateAndSanitize(draft, userId, ErrorKey.AI_RESPONSE_INVALID);

        DraftNewHabitDTO sanitized = result.sections().get(0).habits().get(0).newHabit();
        assertEquals(AiIconCatalog.DEFAULT_ICON, sanitized.iconId());
        assertEquals(5, sanitized.importance());
        assertEquals(1, sanitized.dificulty());
    }

    @Test
    void convertsDuplicateNewHabitToExistingRef() {
        DraftHabitItemDTO duplicate = new DraftHabitItemDTO(null,
                new DraftNewHabitDTO("  read 30MIN ", null, null, null, 3, 3, List.of()),
                "06:00", "06:30");
        RoutineDraftDTO draft = draftWith(section(List.of(duplicate), List.of()));

        RoutineDraftDTO result = validator.validateAndSanitize(draft, userId, ErrorKey.AI_RESPONSE_INVALID);

        DraftHabitItemDTO sanitized = result.sections().get(0).habits().get(0);
        assertEquals(existingHabitId, sanitized.existingHabitId());
        assertNull(sanitized.newHabit());
    }

    @Test
    void acceptsValidDraftAndKeepsStructure() {
        DraftHabitItemDTO ref = new DraftHabitItemDTO(existingHabitId, null, "06:00", "06:30");
        DraftTaskItemDTO newTask = new DraftTaskItemDTO(null,
                new DraftNewTaskDTO("Pack bag", null, "ri:md/MdChecklist", 2, 1,
                        List.of(existingCategoryId.toString()), true),
                "07:00", "07:15");
        RoutineDraftDTO draft = draftWith(section(List.of(ref), List.of(newTask)));

        RoutineDraftDTO result = validator.validateAndSanitize(draft, userId, ErrorKey.AI_RESPONSE_INVALID);

        assertEquals(1, result.sections().size());
        assertEquals(existingHabitId, result.sections().get(0).habits().get(0).existingHabitId());
        assertEquals("Pack bag", result.sections().get(0).tasks().get(0).newTask().name());
    }
}

package beyou.beyouapp.backend.integration.ai;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.ai.AiRoutineConfirmService;
import beyou.beyouapp.backend.domain.ai.dto.DraftHabitItemDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewCategoryDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewHabitDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftSectionDTO;
import beyou.beyouapp.backend.domain.ai.dto.RoutineDraftDTO;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class AiRoutineConfirmRollbackIT extends AbstractIntegrationTest {

    @Autowired private AiRoutineConfirmService confirmService;
    @Autowired private UserRepository userRepository;
    @Autowired private HabitRepository habitRepository;
    @Autowired private CategoryRepository categoryRepository;

    /** Replaces the real routine service so the LAST step of confirm explodes. */
    @MockitoBean private DiaryRoutineService diaryRoutineService;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setName("AI Rollback IT User");
        user.setEmail("ai-rollback-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user = userRepository.saveAndFlush(user);
    }

    @Test
    void rollsBackCategoriesAndHabitsWhenRoutineCreationFails() {
        when(diaryRoutineService.createDiaryRoutine(any(), any(User.class)))
                .thenThrow(new RuntimeException("simulated failure at the last step"));

        RoutineDraftDTO draft = new RoutineDraftDTO(
                "Doomed Routine", null,
                List.of(new DraftNewCategoryDTO("new-1", "Doomed Category", null, null)),
                List.of(new DraftSectionDTO("Morning", null, "06:00", "09:00",
                        List.of(new DraftHabitItemDTO(null,
                                new DraftNewHabitDTO("Doomed habit", null, null, null, 3, 3, List.of("new-1")),
                                "06:00", "06:30")),
                        List.of())),
                null);

        assertThrows(RuntimeException.class, () -> confirmService.confirm(draft, user));

        // the category and habit created in steps 1-2 must have been rolled back
        assertEquals(0, habitRepository.findAllByUserId(user.getId()).size());
        assertEquals(0, categoryRepository.findAllByUserId(user.getId()).orElse(new ArrayList<>()).size());
    }
}

package beyou.beyouapp.backend.unit.ai;

import beyou.beyouapp.backend.domain.ai.dto.DraftHabitItemDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewCategoryDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewHabitDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftNewTaskDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftSectionDTO;
import beyou.beyouapp.backend.domain.ai.dto.DraftTaskItemDTO;
import beyou.beyouapp.backend.domain.ai.dto.RoutineDraftDTO;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutineDraftDtoSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void roundTripsFullDraft() throws Exception {
        UUID existingHabitId = UUID.randomUUID();
        RoutineDraftDTO draft = new RoutineDraftDTO(
                "Morning Routine", "ri:md/MdWbSunny",
                List.of(new DraftNewCategoryDTO("new-1", "Wellness", "ri:md/MdSpa", "desc")),
                List.of(new DraftSectionDTO("Morning", "ri:md/MdWbSunny", "06:00", "09:00",
                        List.of(
                                new DraftHabitItemDTO(existingHabitId, null, "06:00", "06:10"),
                                new DraftHabitItemDTO(null,
                                        new DraftNewHabitDTO("Drink water", "d", "m", "ri:md/MdLocalDrink",
                                                3, 1, List.of("new-1")),
                                        "06:10", "06:20")),
                        List.of(new DraftTaskItemDTO(null,
                                new DraftNewTaskDTO("Breakfast", null, "ri:md/MdBreakfastDining",
                                        2, 1, List.of("new-1"), false),
                                "06:20", "06:40")))),
                Set.of(WeekDay.Monday, WeekDay.Friday));

        String json = objectMapper.writeValueAsString(draft);
        RoutineDraftDTO back = objectMapper.readValue(json, RoutineDraftDTO.class);

        assertEquals(draft, back);
        // wire-format names the frontend depends on:
        assertTrue(json.contains("\"newCategories\""));
        assertTrue(json.contains("\"existingHabitId\""));
        assertTrue(json.contains("\"newHabit\""));
        assertTrue(json.contains("\"dificulty\""));      // habit wire-format typo preserved
        assertTrue(json.contains("\"difficulty\""));     // task spells it correctly
        assertTrue(json.contains("\"scheduleDays\""));
    }
}

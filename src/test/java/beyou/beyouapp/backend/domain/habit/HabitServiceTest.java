package beyou.beyouapp.backend.domain.habit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.habit.dto.EditHabitDTO;
import beyou.beyouapp.backend.exceptions.habit.HabitNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
public class HabitServiceTest {
    @Mock
    private HabitRepository habitRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private XpByLevelRepository xpByLevelRepository;

    @InjectMocks
    private HabitService habitService;

    @Test
    public void shouldGetHabitSuccessfully(){
        UUID habitId = UUID.randomUUID();
        Habit mockHabit = new Habit();
        
        when(habitRepository.findById(habitId)).thenReturn(Optional.of(mockHabit));

        Habit testHabit = habitService.getHabit(habitId);

        assertEquals(mockHabit, testHabit);
    }

    @Test
    public void shouldGetAllHabitsSuccessfully(){
        User user = new User();
        UUID userId = UUID.randomUUID();
        user.setId(userId);

        ArrayList<Habit> habits = new ArrayList<>(List.of());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(habitRepository.findAllByUserId(userId)).thenReturn(habits);

        ArrayList<Habit> assertResponse = habitService.getHabits(userId);

        assertEquals(habits, assertResponse);
    }

    @Test
    public void shouldCreateHabitSuccessfully(){
        User user = new User();
        UUID userId = UUID.randomUUID();
        user.setId(userId);

        Category newCategory = new Category();
        List<Category> categories = new ArrayList<>(List.of(newCategory));

        CreateHabitDTO createHabitDTO = new CreateHabitDTO(userId.toString(), 
        "name", "", "", "", 2, 2, 
        categories, 0, 0);

        XpByLevel xpByLevel = new XpByLevel(0, 0);
        ResponseEntity<Map<String, String>> response = ResponseEntity.ok().body(Map.of("success", "Habit saved successfully"));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(xpByLevelRepository.findByLevel(0)).thenReturn(xpByLevel);
        when(xpByLevelRepository.findByLevel(0 + 1)).thenReturn(xpByLevel);

        ResponseEntity<Map<String, String>> assertResponse = habitService.createHabit(createHabitDTO);

        assertEquals(response.getBody(), assertResponse.getBody());
        assertEquals(response.getStatusCode(), assertResponse.getStatusCode());
    }

    @Test
    public void shouldEditHabitSuccessfully(){
        Habit habit = new Habit();
        UUID habitId = UUID.randomUUID();
        habit.setId(habitId);

        EditHabitDTO editHabitDTO = new EditHabitDTO(habitId, "editedName", 
        "", "", "", 0, 0, null);
        ResponseEntity<Map<String, String>> response = ResponseEntity.ok().body(Map.of("success", "Habit edited successfully"));

        when(habitRepository.findById(habitId)).thenReturn(Optional.of(habit));

        ResponseEntity<Map<String, String>> assertResponse = habitService.editHabit(editHabitDTO);

        assertEquals(response.getBody(), assertResponse.getBody());
        assertEquals(response.getStatusCode(), assertResponse.getStatusCode());
    }

    @Test
    public void shouldDeleteHabitSuccessfully(){
        Habit habit = new Habit();
        UUID habitId = UUID.randomUUID();
        habit.setId(habitId);
        ResponseEntity<Map<String, String>> response = ResponseEntity.ok().body(Map.of("success", "habit deleted successfully"));

        when(habitRepository.findById(habitId)).thenReturn(Optional.of(habit));

        ResponseEntity<Map<String, String>> assertResponse = habitService.deleteHabit(habitId);

        assertEquals(response.getBody(), assertResponse.getBody());
        assertEquals(response.getStatusCode(), assertResponse.getStatusCode());
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

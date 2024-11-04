package beyou.beyouapp.backend.domain.habit;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.habit.dto.EditHabitDTO;
import beyou.beyouapp.backend.exceptions.habit.HabitNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

@Service
public class HabitService {
    @Autowired
    private HabitRepository habitRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private XpByLevelRepository xpByLevelRepository;

    public Habit getHabit(UUID habitId){
        return habitRepository.findById(habitId)
        .orElseThrow(() -> new HabitNotFound("Habit not found"));
    }

    public ArrayList<Habit> getHabits(String userId){
        User user = userRepository.findById(UUID.fromString(userId))
        .orElseThrow(() -> new UserNotFound("User not found"));

        try{
            return habitRepository.findAllByUserId(user.getId());
        }catch(Exception e){
            throw e;
        }
    }

    public ResponseEntity<Map<String, String>> createHabit(CreateHabitDTO createHabitDTO){
        User user = userRepository.findById(UUID.fromString(createHabitDTO.userId()))
        .orElseThrow(() -> new UserNotFound("User not found"));

        XpByLevel actualBaseXp = xpByLevelRepository.findByLevel(createHabitDTO.level());
        XpByLevel nextLevelXp = xpByLevelRepository.findByLevel(createHabitDTO.level() + 1);

        Habit newHabit = new Habit(createHabitDTO, nextLevelXp.getXp(), actualBaseXp.getXp(), user);

        try{
            habitRepository.save(newHabit);
            return ResponseEntity.ok().body(Map.of("success", "Habit saved successfully"));
        }catch(Exception e){
            return ResponseEntity.badRequest().body(Map.of("error", "errorTryingCreateHabit"));
        }
    }

    public ResponseEntity<Map<String, String>> editHabit(EditHabitDTO editHabitDTO){
        Habit habitToEdit = getHabit(editHabitDTO.habitId());

        habitToEdit.setName(editHabitDTO.name());
        habitToEdit.setDescription(editHabitDTO.description());
        habitToEdit.setIconId(editHabitDTO.iconId());
        habitToEdit.setMotivationalPhrase(editHabitDTO.motivationalPhrase());
        habitToEdit.setImportance(editHabitDTO.importance());
        habitToEdit.setDificulty(editHabitDTO.dificulty());
        habitToEdit.setCategories(editHabitDTO.categories());

        try{
            habitRepository.save(habitToEdit);
            return ResponseEntity.ok().body(Map.of("success", "Habit edited successfully"));
        }catch(Exception e){
            return ResponseEntity.badRequest().body(Map.of("error", "errorTryingToEdit"));
        }
    }

    public ResponseEntity<Map<String, String>> deleteHabit(UUID habitId){
        try{
            Habit habitToDelete = getHabit(habitId);
            habitRepository.delete(habitToDelete);
            return ResponseEntity.ok().body(Map.of("success", "habit deleted successfully"));
        }catch(Exception e){
            return ResponseEntity.badRequest().body(Map.of("error", "errorTringDelete"));
        }
    }
}

package beyou.beyouapp.backend.domain.habit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
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

    @Autowired
    private CategoryService categoryService;

    public Habit getHabit(UUID habitId){
        return habitRepository.findById(habitId)
        .orElseThrow(() -> new HabitNotFound("Habit not found"));
    }

    public ArrayList<Habit> getHabits(UUID userId){
        try{
            return habitRepository.findAllByUserId(userId);
        }catch(Exception e){
            throw e;
        }
    }

    public ResponseEntity<Map<String, String>> createHabit(CreateHabitDTO createHabitDTO, UUID userId){
        User user = userRepository.findById(userId)
        .orElseThrow(() -> new UserNotFound("User not found"));

        XpByLevel actualBaseXp = xpByLevelRepository.findByLevel(createHabitDTO.level());
        XpByLevel nextLevelXp = xpByLevelRepository.findByLevel(createHabitDTO.level() + 1);

        ArrayList<Category> categories = new ArrayList<>();
        int numberOfCategories = createHabitDTO.categoriesId().size();
        for(int i = 0; i < numberOfCategories; i++){
            Category category = categoryService.getCategory(createHabitDTO.categoriesId().get(i));
            categories.add(category);
        }

        Habit newHabit = new Habit(createHabitDTO, categories, nextLevelXp.getXp(), actualBaseXp.getXp(), user);

        try{
            habitRepository.save(newHabit);
            return ResponseEntity.ok().body(Map.of("success", "Habit saved successfully"));
        }catch(Exception e){
            return ResponseEntity.badRequest().body(Map.of("error", "errorTryingCreateHabit"));
        }
    }

    public ResponseEntity<Map<String, String>> editHabit(EditHabitDTO editHabitDTO, UUID userId){
        Habit habitToEdit = getHabit(editHabitDTO.habitId());
        if(!habitToEdit.getUser().getId().equals(userId)){
            throw new HabitNotFound("The habit is not from the user in context");
        }
        habitToEdit.setName(editHabitDTO.name());
        habitToEdit.setDescription(editHabitDTO.description());
        habitToEdit.setIconId(editHabitDTO.iconId());
        habitToEdit.setMotivationalPhrase(editHabitDTO.motivationalPhrase());
        habitToEdit.setImportance(editHabitDTO.importance());
        habitToEdit.setDificulty(editHabitDTO.dificulty());

        List<Category> categoriesEdit = new ArrayList<>();
        int numberOfCategories = editHabitDTO.categoriesId().size();
        for(int i = 0; i < numberOfCategories; i++){
            Category category = categoryService.getCategory(editHabitDTO.categoriesId().get(i));
            categoriesEdit.add(category);
        }
        
        habitToEdit.setCategories(categoriesEdit);
        try{
            habitRepository.save(habitToEdit);
            return ResponseEntity.ok().body(Map.of("success", "Habit edited successfully"));
        }catch(Exception e){
            return ResponseEntity.badRequest().body(Map.of("error", "errorTryingToEdit"));
        }
    }

    public ResponseEntity<Map<String, String>> deleteHabit(UUID habitId, UUID userId){
        try{
            Habit habitToDelete = getHabit(habitId);
            if(!habitToDelete.getUser().getId().equals(userId)){
                throw new HabitNotFound("The habit are not from the user in context");
            }
            habitRepository.delete(habitToDelete);
            return ResponseEntity.ok().body(Map.of("success", "habit deleted successfully"));
        }catch(Exception e){
            return ResponseEntity.badRequest().body(Map.of("error", "errorTringDelete"));
        }
    }
}

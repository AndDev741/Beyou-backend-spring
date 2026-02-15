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
import beyou.beyouapp.backend.domain.habit.dto.HabitResponseDTO;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.habit.dto.EditHabitDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.habit.HabitNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;

@Service
@Slf4j
@RequiredArgsConstructor
public class HabitService {
    @Autowired
    private final HabitRepository habitRepository;

    @Autowired
    private final UserRepository userRepository;

    @Autowired
    private final XpByLevelRepository xpByLevelRepository;

    @Autowired
    private final CategoryService categoryService;

    @Autowired
    private final HabitMapper habitMapper;

    @Autowired
    private final DiaryRoutineRepository diaryRoutineRepository;

    public Habit getHabit(UUID habitId){
        return habitRepository.findById(habitId)
        .orElseThrow(() -> new HabitNotFound("Habit not found"));
    }

    public List<HabitResponseDTO> getHabits(UUID userId){
        ArrayList<Habit> habits = habitRepository.findAllByUserId(userId);
        return habits.stream()
                .map(habitMapper::toResponseDTO)
                .toList();
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

        Habit newHabit = habitMapper.toEntity(createHabitDTO, categories, actualBaseXp, nextLevelXp, user);

        try{
            habitRepository.save(newHabit);
            return ResponseEntity.ok().body(Map.of("success", "Habit saved successfully"));
        }catch(Exception e){
            throw new BusinessException(ErrorKey.HABIT_CREATE_FAILED, "Error trying to create habit");
        }
    }

    public ResponseEntity<Map<String, String>> editHabit(EditHabitDTO editHabitDTO, UUID userId){
        Habit habitToEdit = getHabit(editHabitDTO.habitId());
        if(!habitToEdit.getUser().getId().equals(userId)){
            throw new BusinessException(ErrorKey.HABIT_NOT_OWNED, "The habit is not from the user in context");
        }

        List<Category> categoriesEdit = new ArrayList<>();
        int numberOfCategories = editHabitDTO.categoriesId().size();
        for(int i = 0; i < numberOfCategories; i++){
            Category category = categoryService.getCategory(editHabitDTO.categoriesId().get(i));
            categoriesEdit.add(category);
        }
        
        habitMapper.updateEntity(habitToEdit, editHabitDTO, categoriesEdit);
        try{
            habitRepository.save(habitToEdit);
            return ResponseEntity.ok().body(Map.of("success", "Habit edited successfully"));
        }catch(Exception e){
            throw new BusinessException(ErrorKey.HABIT_EDIT_FAILED, "Error trying to edit habit");
        }
    }

    @Transactional
    public ResponseEntity<Map<String, String>> deleteHabit(UUID habitId, UUID userId){
        Habit habitToDelete = getHabit(habitId);
        if(!habitToDelete.getUser().getId().equals(userId)){
            throw new BusinessException(ErrorKey.HABIT_NOT_OWNED, "The habit are not from the user in context");
        }
        if (isHabitLinkedToRoutine(habitId, userId)) {
            throw new BusinessException(ErrorKey.HABIT_IN_ROUTINE, "This habit is used in some routine, please remove it first");
        }
        try{
            habitRepository.delete(habitToDelete);
            return ResponseEntity.ok().body(Map.of("success", "habit deleted successfully"));
        }catch(DataIntegrityViolationException e){
            throw new BusinessException(ErrorKey.HABIT_IN_ROUTINE, "This habit is used in some routine, please remove it first");
        }catch(Exception e){
            throw new BusinessException(ErrorKey.HABIT_DELETE_FAILED, "Error trying to delete habit");
        }
    }

    private boolean isHabitLinkedToRoutine(UUID habitId, UUID userId) {
        List<DiaryRoutine> routines = diaryRoutineRepository.findAllByUserId(userId);
        return routines.stream()
            .flatMap(routine -> routine.getRoutineSections().stream())
            .flatMap(section -> section.getHabitGroups().stream())
            .anyMatch(group -> group.getHabit() != null && habitId.equals(group.getHabit().getId()));
    }

    public Habit editEntity(Habit habitToEdit){
        return habitRepository.save(habitToEdit);
    }
}

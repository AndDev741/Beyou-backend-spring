package beyou.beyouapp.backend.domain.goal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.EditGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.util.GoalXpCalculator;
import beyou.beyouapp.backend.exceptions.goal.GoalNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class GoalService {
    private final GoalRepository goalRepository;
    private final UserRepository userRepository;
    private final CategoryService categoryService;

    public GoalService(GoalRepository goalRepository,
                       UserRepository userRepository,
                       CategoryService categoryService) {
        this.goalRepository = goalRepository;
        this.userRepository = userRepository;
        this.categoryService = categoryService;
    }

    public Goal getGoal(UUID goalId) {
        return goalRepository.findById(goalId)
                .orElseThrow(() -> new GoalNotFound("Goal not found"));
    }

    public List<Goal> getAllGoals(UUID userId) {
        return goalRepository.findAllByUserId(userId)
                .orElseThrow(() -> new UserNotFound("User not found when trying to get goals"));
    }

    public ResponseEntity<Map<String, String>> createGoal(CreateGoalRequestDTO dto, UUID userId) {
        log.info("[LOG] Creating Goal with DTO => {}", dto);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFound("User not found when trying to create goal"));
        List<Category> categories = dto.categoriesId().stream()
                .map(categoryService::getCategory)
                .toList();
        
        Goal goal = new Goal(dto, categories, user);
        try {
            goalRepository.save(goal);
            return ResponseEntity.ok(Map.of("success", "Goal created successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Error trying to create goal"));
        }
    }

    public ResponseEntity<Map<String, String>> editGoal(EditGoalRequestDTO dto, UUID userId) {
        Goal goal = getGoal(dto.goalId());
        checkIfGoalIsFromTheUserInContext(goal, userId);

        goal.setName(dto.name());
        goal.setIconId(dto.iconId());
        goal.setDescription(dto.description());
        goal.setTargetValue(dto.targetValue());
        goal.setUnit(dto.unit());
        goal.setCurrentValue(dto.currentValue());
        goal.setComplete(dto.complete());
        goal.setCategories(new ArrayList<>(
            dto.categoriesId().stream()
                .map(categoryService::getCategory)
                .toList()
        ));
        goal.setMotivation(dto.motivation());
        goal.setStartDate(dto.startDate());
        goal.setEndDate(dto.endDate());
        goal.setStatus(dto.status());
        goal.setTerm(dto.term());
        try {
            goalRepository.save(goal);
            return ResponseEntity.ok(Map.of("success", "Goal edited successfully"));
        } catch (Exception e) {
            log.error("ERROR TRYING TO EDIT GOAL", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Error trying to edit goal"));
        }
    }

    public ResponseEntity<Map<String, String>> deleteGoal(UUID goalId, UUID userId) {
        Goal goal = getGoal(goalId);
        checkIfGoalIsFromTheUserInContext(goal, userId);

        try {
            goalRepository.delete(goal);
            return ResponseEntity.ok(Map.of("success", "Goal deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Error trying to delete goal"));
        }
    }

    public Goal editEntity(Goal goal) {
        return goalRepository.save(goal);
    }

    public Goal checkGoal(UUID goalId, UUID userId) {
        Goal goal = getGoal(goalId);
        checkIfGoalIsFromTheUserInContext(goal, userId);

        double xp = GoalXpCalculator.calculateXp(goal);
        goal.setXpReward(xp);

        if(goal.getComplete() == null || !goal.getComplete()){
            setGoalAsCompleted(goal, xp);
        }else{
            removeCompletedOfAGoal(goal, xp);
        }

        try {
            goalRepository.save(goal);
            return goal;
        } catch (Exception e) {
           throw new RuntimeException(e);
        }
    } 

    private void setGoalAsCompleted(Goal goal, double xpReward){
        goal.setComplete(true);   
        goal.setStatus(GoalStatus.COMPLETED);
        goal.setCompleteDate(LocalDate.now());

        categoryService.updateCategoriesXpAndLevel(goal.getCategories(), xpReward); 
    }

    private void removeCompletedOfAGoal(Goal goal, double xpReward){
        goal.setComplete(false);
        goal.setStatus(GoalStatus.IN_PROGRESS);
        goal.setCompleteDate(null);

        categoryService.removeXpFromCategories(goal.getCategories(), xpReward);
    }

    public Goal increaseCurrentValue (UUID goalId, UUID userId) {
        Goal goal = getGoal(goalId);
        checkIfGoalIsFromTheUserInContext(goal, userId);

        goal.setCurrentValue(goal.getCurrentValue() + 1);
        try {
            goalRepository.save(goal);
            return goal;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void checkIfGoalIsFromTheUserInContext(Goal goal, UUID userId) {
        if (!goal.getUser().getId().equals(userId)) {
            throw new GoalNotFound("The goal isn't of the user in context");
        }
    }

}
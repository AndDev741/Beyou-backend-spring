package beyou.beyouapp.backend.domain.goal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.EditGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.GoalResponseDTO;
import beyou.beyouapp.backend.domain.goal.util.GoalXpCalculator;
import beyou.beyouapp.backend.exceptions.goal.GoalNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class GoalService {
    private final GoalRepository goalRepository;
    private final CategoryService categoryService;
    private final GoalMapper goalMapper;
    private final XpCalculatorService xpCalculatorService;

    public Goal getGoal(UUID goalId) {
        return goalRepository.findById(goalId)
                .orElseThrow(() -> new GoalNotFound("Goal not found"));
    }

    public List<GoalResponseDTO> getAllGoals(UUID userId) {
        return goalRepository.findAllByUserId(userId)
                .orElseThrow(() -> new UserNotFound("User not found when trying to get goals"))
                .stream()
                .map(goalMapper::toResponseDTO)
                .toList();
    }

    public ResponseEntity<Map<String, String>> createGoal(CreateGoalRequestDTO dto, User user) {
        log.info("[LOG] Creating Goal with DTO => {}", dto);
        List<Category> categories = dto.categoriesId().stream()
                .map(categoryService::getCategory)
                .toList();
        
        Goal goal = goalMapper.toEntity(dto, categories, user);
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

        List<Category> categories = dto.categoriesId().stream()
                .map(categoryService::getCategory)
                .toList();
        goalMapper.updateEntity(goal, dto, categories);
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

    @Transactional
    public GoalResponseDTO checkGoal(UUID goalId, UUID userId) {
        Goal goal = getGoal(goalId);
        checkIfGoalIsFromTheUserInContext(goal, userId);

        double xp = GoalXpCalculator.calculateXp(goal);

        if(goal.getComplete() == null || !goal.getComplete()){
            setGoalAsCompletedAndAddXp(goal, xp);
        }else{
            removeCompletedOfAGoalAndRemoveXp(goal, xp);
        }

        return goalMapper.toResponseDTO(goal);
    } 

    private void setGoalAsCompletedAndAddXp(Goal goal, double xpReward){
        goal.setComplete(true);   
        goal.setStatus(GoalStatus.COMPLETED);
        goal.setCompleteDate(LocalDate.now());

        xpCalculatorService.addXpToUserGoalAndCategoriesAndPersist(xpReward, goal, goal.getCategories());
    }

    private void removeCompletedOfAGoalAndRemoveXp(Goal goal, double xpReward){
        goal.setComplete(false);
        goal.setStatus(GoalStatus.IN_PROGRESS);
        goal.setCompleteDate(null);

        xpCalculatorService.removeXpOfUserGoalAndCategoriesAndPersist(xpReward, goal, goal.getCategories());
    }

    public GoalResponseDTO increaseCurrentValue (UUID goalId, UUID userId) {
        Goal goal = getGoal(goalId);
        checkIfGoalIsFromTheUserInContext(goal, userId);

        goal.setCurrentValue(goal.getCurrentValue() + 1);
        try {
            goalRepository.save(goal);
            return goalMapper.toResponseDTO(goal);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public GoalResponseDTO decreaseCurrentValue (UUID goalId, UUID userId) {
        Goal goal = getGoal(goalId);
        checkIfGoalIsFromTheUserInContext(goal, userId);

        goal.setCurrentValue(goal.getCurrentValue() - 1);
        try {
            goalRepository.save(goal);
            return goalMapper.toResponseDTO(goal);
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

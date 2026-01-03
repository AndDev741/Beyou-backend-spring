package beyou.beyouapp.backend.controllers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.goal.GoalService;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.EditGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.GoalResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

@RestController
@RequestMapping("/goal")
public class GoalController {
    private final GoalService goalService;
    private final AuthenticatedUser authenticatedUser;

    public GoalController(GoalService goalService, AuthenticatedUser authenticatedUser) {
        this.goalService = goalService;
        this.authenticatedUser = authenticatedUser;
    }

    @GetMapping
    public List<GoalResponseDTO> getGoals() {
        User user = authenticatedUser.getAuthenticatedUser();
        return goalService.getAllGoals(user.getId());
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> createGoal(@RequestBody CreateGoalRequestDTO dto) {
        User user = authenticatedUser.getAuthenticatedUser();
        return goalService.createGoal(dto, user);
    }

    @PutMapping
    public ResponseEntity<Map<String, String>> editGoal(@RequestBody EditGoalRequestDTO dto) {
        User user = authenticatedUser.getAuthenticatedUser();
        return goalService.editGoal(dto, user.getId());
    }

    @DeleteMapping("/{goalId}")
    public ResponseEntity<Map<String, String>> deleteGoal(@PathVariable UUID goalId) {
        User user = authenticatedUser.getAuthenticatedUser();
        return goalService.deleteGoal(goalId, user.getId());
    }

    @PutMapping("/complete")
    public GoalResponseDTO setAsComplete(@RequestBody UUID goalId) {
        User user = authenticatedUser.getAuthenticatedUser();
        
        try {
            return goalService.checkGoal(goalId, user.getId());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } 
    }

    @PutMapping("/increase")
    public GoalResponseDTO increaseCurrentValue(@RequestBody UUID goalId) {
        User user = authenticatedUser.getAuthenticatedUser();
        
        try {
            return goalService.increaseCurrentValue(goalId, user.getId());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } 
    }

    @PutMapping("/decrease")
    public GoalResponseDTO decreaseCurrentValue(@RequestBody UUID goalId) {
        User user = authenticatedUser.getAuthenticatedUser();
        
        try {
            return goalService.decreaseCurrentValue(goalId, user.getId());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } 
    }
}

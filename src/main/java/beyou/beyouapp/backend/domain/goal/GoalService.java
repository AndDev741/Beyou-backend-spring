package beyou.beyouapp.backend.domain.goal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.common.RefreshUiDtoBuilder;
import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.EditGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.GoalResponseDTO;
import beyou.beyouapp.backend.domain.goal.util.GoalXpCalculator;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.goal.GoalNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
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
    private final RefreshUiDtoBuilder refreshUiDtoBuilder;
    private final UserCacheEvictService userCacheEvictService;
    private final UserRepository userRepository;

    public Goal getGoal(UUID goalId) {
        return goalRepository.findById(goalId)
                .orElseThrow(() -> new GoalNotFound("Goal not found"));
    }

    // Transactional so the mapper can walk lazy category relations: OSIV covers
    // this on the request thread, but agent tools run on a boundedElastic thread.
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "goals", key = "#userId")
    public List<GoalResponseDTO> getAllGoals(UUID userId) {
        return goalRepository.findAllByUserId(userId)
                .orElseThrow(() -> new UserNotFound("User not found when trying to get goals"))
                .stream()
                .map(goalMapper::toResponseDTO)
                .toList();
    }

    public ResponseEntity<Map<String, String>> createGoal(CreateGoalRequestDTO dto, UUID userId) {
        log.info("[LOG] Creating Goal with DTO => {}", dto);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFound("User not found when trying to create a goal"));

        List<Category> categories = dto.categoriesId().stream()
                .distinct()
                .map(catId -> categoryService.getCategory(catId, userId))
                .toList();

        Goal goal = goalMapper.toEntity(dto, categories, user);
        goal.setParent(resolveParent(null, dto.parentId(), userId));
        try {
            goalRepository.save(goal);
            userCacheEvictService.evictAllUserCaches(userId);
            return ResponseEntity.ok(Map.of("success", "Goal created successfully"));
        } catch (Exception e) {
            throw new BusinessException(ErrorKey.GOAL_CREATE_FAILED, "Error trying to create goal");
        }
    }

    public ResponseEntity<Map<String, String>> editGoal(EditGoalRequestDTO dto, UUID userId) {
        Goal goal = getGoal(dto.goalId());
        checkIfGoalIsFromTheUserInContext(goal, userId);

        List<Category> categories = dto.categoriesId().stream()
                .distinct()
                .map(catId -> categoryService.getCategory(catId, userId))
                .toList();
        goalMapper.updateEntity(goal, dto, categories);
        goal.setParent(resolveParent(goal, dto.parentId(), userId));
        try {
            goalRepository.save(goal);
            userCacheEvictService.evictAllUserCaches(userId);
            return ResponseEntity.ok(Map.of("success", "Goal edited successfully"));
        } catch (Exception e) {
            log.error("ERROR TRYING TO EDIT GOAL", e);
            throw new BusinessException(ErrorKey.GOAL_EDIT_FAILED, "Error trying to edit goal");
        }
    }

    public ResponseEntity<Map<String, String>> deleteGoal(UUID goalId, UUID userId) {
        Goal goal = getGoal(goalId);
        checkIfGoalIsFromTheUserInContext(goal, userId);

        try {
            goalRepository.delete(goal);
            userCacheEvictService.evictAllUserCaches(userId);
            return ResponseEntity.ok(Map.of("success", "Goal deleted successfully"));
        } catch (Exception e) {
            throw new BusinessException(ErrorKey.GOAL_DELETE_FAILED, "Error trying to delete goal");
        }
    }

    public Goal editEntity(Goal goal) {
        return goalRepository.save(goal);
    }

    @Transactional
    public RefreshUiDTO checkGoal(UUID goalId, UUID userId) {
        Goal goal = getGoal(goalId);
        checkIfGoalIsFromTheUserInContext(goal, userId);

        double xp = GoalXpCalculator.calculateXp(goal);

        if(goal.getComplete() == null || !goal.getComplete()){
            setGoalAsCompletedAndAddXp(goal, xp);
        }else{
            removeCompletedOfAGoalAndRemoveXp(goal, xp);
        }

        userCacheEvictService.evictAllUserCaches(userId);
        return refreshUiDtoBuilder.buildRefreshUiDto(
            LocalDate.now(),
            null,
            goal.getCategories(),
            null,
            goal.getUser()
        );
    }

    private void setGoalAsCompletedAndAddXp(Goal goal, double xpReward){
        goal.setComplete(true);   
        goal.setStatus(GoalStatus.COMPLETED);
        goal.setCompleteDate(LocalDate.now());

        xpCalculatorService.addXpToUserGoalAndCategoriesAndPersist(goal.getUser(), xpReward, goal, goal.getCategories());
    }

    private void removeCompletedOfAGoalAndRemoveXp(Goal goal, double xpReward){
        goal.setComplete(false);
        goal.setStatus(GoalStatus.IN_PROGRESS);
        goal.setCompleteDate(null);

        xpCalculatorService.removeXpOfUserGoalAndCategoriesAndPersist(goal.getUser(), xpReward, goal, goal.getCategories());
    }

    /**
     * Transactional because of who calls it, not because of what it writes.
     *
     * <p>The AI agent's tools run inside the SSE Flux, on a reactor thread, and Open
     * Session In View binds its EntityManager to the servlet request thread — so the
     * agent gets no ambient session. Without one, getGoal returns a detached Goal and
     * toResponseDTO walks the lazy {@code categories} @ManyToMany, which is a
     * LazyInitializationException rolling the whole increment back. Over HTTP the same
     * code worked, because OSIV held a session open for the request; that is why this
     * only ever failed through the chat. checkGoal already carried the annotation for
     * the same reason.
     */
    @Transactional
    public GoalResponseDTO increaseCurrentValue(UUID goalId, Double value, UUID userId) {
        Goal goal = getGoal(goalId);
        checkIfGoalIsFromTheUserInContext(goal, userId);

        double increment = (value != null && value > 0) ? value : 1.0;
        goal.setCurrentValue(goal.getCurrentValue() + increment);
        // Progress is what says the goal has started, so the first increment moves
        // it out of NOT_STARTED without anyone having to edit the goal. A completed
        // goal is left alone: only PUT /goal/complete flips that, because that is
        // where the XP moves.
        if (goal.getStatus() == GoalStatus.NOT_STARTED) {
            goal.setStatus(GoalStatus.IN_PROGRESS);
        }
        startParentIfNotStarted(goal);
        try {
            goalRepository.save(goal);
            userCacheEvictService.evictAllUserCaches(userId);
            return goalMapper.toResponseDTO(goal);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public GoalResponseDTO increaseCurrentValue(UUID goalId, UUID userId) {
        return increaseCurrentValue(goalId, 1.0, userId);
    }

    /** Transactional for the same reason as {@link #increaseCurrentValue}. */
    @Transactional
    public GoalResponseDTO decreaseCurrentValue(UUID goalId, Double value, UUID userId) {
        Goal goal = getGoal(goalId);
        checkIfGoalIsFromTheUserInContext(goal, userId);

        double decrement = (value != null && value > 0) ? value : 1.0;
        goal.setCurrentValue(Math.max(0, goal.getCurrentValue() - decrement));
        // Going back down does not un-start the goal. Someone who corrects a wrong
        // increment has still started it; sliding back to NOT_STARTED on the way to
        // zero would flicker the status on every correction. Editing the goal is how
        // you say it never started.
        try {
            goalRepository.save(goal);
            userCacheEvictService.evictAllUserCaches(userId);
            return goalMapper.toResponseDTO(goal);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public GoalResponseDTO decreaseCurrentValue(UUID goalId, UUID userId) {
        return decreaseCurrentValue(goalId, 1.0, userId);
    }

    /**
     * How deep a chain of goals may go: a big goal, a medium one under it, a small one
     * under that. Three is what the roadmap card describes, and it is enforced here
     * once rather than in each client, so the mobile picker and the web picker can
     * only ever pre-filter what this method would refuse anyway.
     */
    public static final int MAX_DEPTH = 3;

    /**
     * Resolve and validate the parent a goal is being placed under.
     *
     * <p>{@code null} means top level and always passes. Otherwise the parent must
     * exist, belong to the same user ({@link ErrorKey#GOAL_NOT_OWNED}), must not be the
     * goal itself or one of its descendants ({@link ErrorKey#GOAL_PARENT_CYCLE}), and
     * the resulting chain must fit in {@link #MAX_DEPTH} levels
     * ({@link ErrorKey#GOAL_DEPTH_EXCEEDED}). Depth is measured both ways: the
     * ancestors above the new parent plus the tallest subtree already hanging under
     * {@code goal}, because moving a goal that has children can overflow from below
     * while looking fine from above.
     *
     * <p>{@code goal} is null on create (nothing hangs under a goal that does not exist
     * yet). Nothing here is duplicated in the clients on purpose: the rule lives once.
     */
    public Goal resolveParent(Goal goal, UUID parentId, UUID userId) {
        if (parentId == null) {
            return null;
        }
        if (goal != null && parentId.equals(goal.getId())) {
            throw new BusinessException(ErrorKey.GOAL_PARENT_CYCLE, "A goal cannot be its own parent");
        }
        Goal parent = getGoal(parentId);
        checkIfGoalIsFromTheUserInContext(parent, userId);

        List<Goal> all = goalRepository.findAllByUserId(userId).orElse(List.of());

        // Walk up from the parent. Meeting `goal` on the way means the parent is one of
        // its descendants, which is the cycle. The visited set guards against a chain
        // already broken in the database looping forever.
        int ancestors = 0;
        Set<UUID> visited = new HashSet<>();
        Goal cursor = parent;
        while (cursor != null) {
            if (goal != null && cursor.getId().equals(goal.getId())) {
                throw new BusinessException(ErrorKey.GOAL_PARENT_CYCLE,
                        "The chosen parent is a sub-goal of this goal");
            }
            if (!visited.add(cursor.getId())) {
                break;
            }
            ancestors++;
            cursor = findById(all, cursor.getParentId());
        }

        int below = goal == null ? 0 : subtreeHeight(goal.getId(), all, new HashSet<>());
        // ancestors counts the parent and everything above it; +1 is the goal itself.
        if (ancestors + 1 + below > MAX_DEPTH) {
            throw new BusinessException(ErrorKey.GOAL_DEPTH_EXCEEDED,
                    "Goals can be nested at most " + MAX_DEPTH + " levels deep");
        }
        return parent;
    }

    private static Goal findById(List<Goal> all, UUID id) {
        if (id == null) return null;
        for (Goal g : all) {
            if (id.equals(g.getId())) return g;
        }
        return null;
    }

    /** Longest chain of descendants under {@code id}: 0 for a leaf, 1 for one level of children. */
    private static int subtreeHeight(UUID id, List<Goal> all, Set<UUID> visited) {
        if (!visited.add(id)) return 0;
        int deepest = 0;
        for (Goal g : all) {
            if (id.equals(g.getParentId())) {
                deepest = Math.max(deepest, 1 + subtreeHeight(g.getId(), all, visited));
            }
        }
        return deepest;
    }

    /**
     * Progress in a sub-goal is what says the parent has started, the same way the
     * goal's own first increment moves it out of NOT_STARTED. Only that transition:
     * completion still belongs to PUT /goal/complete and its XP.
     */
    private void startParentIfNotStarted(Goal goal) {
        Goal parent = goal.getParent();
        if (parent != null && parent.getStatus() == GoalStatus.NOT_STARTED) {
            parent.setStatus(GoalStatus.IN_PROGRESS);
            goalRepository.save(parent);
        }
    }

    /**
     * Re-parent a goal without touching anything else. Exists for the agent, which
     * otherwise has to resend all fifteen fields of an edit to link two goals.
     * {@code parentId} null moves the goal to the top level.
     */
    @Transactional
    public GoalResponseDTO moveUnder(UUID goalId, UUID parentId, UUID userId) {
        Goal goal = getGoal(goalId);
        checkIfGoalIsFromTheUserInContext(goal, userId);
        goal.setParent(resolveParent(goal, parentId, userId));
        goalRepository.save(goal);
        userCacheEvictService.evictAllUserCaches(userId);
        return goalMapper.toResponseDTO(goal);
    }

    public void checkIfGoalIsFromTheUserInContext(Goal goal, UUID userId) {
        if (!goal.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorKey.GOAL_NOT_OWNED, "The goal isn't of the user in context");
        }
    }

}

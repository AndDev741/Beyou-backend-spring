package beyou.beyouapp.backend.domain.habit;

import java.util.ArrayList;
import beyou.beyouapp.backend.domain.xpday.XpDayOwnerType;
import beyou.beyouapp.backend.domain.xpday.XpDayRecorder;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.habit.dto.HabitResponseDTO;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.habit.dto.EditHabitDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.habit.HabitNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;

@Service
@Slf4j
@RequiredArgsConstructor
public class HabitService {
    @Autowired
    private final HabitRepository habitRepository;
    private final XpDayRecorder xpDayRecorder;

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

    private final UserCacheEvictService userCacheEvictService;

    private final EntityCheckDayRepository entityCheckDayRepository;

    public Habit getHabit(UUID habitId){
        return habitRepository.findById(habitId)
        .orElseThrow(() -> new HabitNotFound("Habit not found"));
    }

    /**
     * The same lookup, refusing habits that belong to somebody else.
     *
     * <p>Lives here rather than in each caller because the check is a property of
     * reading a habit by a client-supplied id, not of the feature doing the reading.
     * The routine mapper did the unchecked lookup and let one account embed another's
     * habit in its own routine — after which checking that routine incremented the
     * victim's streak and moved their category XP, and handed their habit back in the
     * response. Same shape as {@code CategoryService.getCategory(id, userId)}.
     */
    public Habit getOwnedHabit(UUID habitId, UUID userId){
        Habit habit = getHabit(habitId);
        if (habit.getUser() == null || !habit.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorKey.HABIT_NOT_OWNED,
                    "The habit is not from the user in context");
        }
        return habit;
    }

    // Transactional so the mapper can walk lazy habitGroups: OSIV covers this
    // on the request thread, but agent tools run on a boundedElastic thread.
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "habits", key = "#userId")
    public List<HabitResponseDTO> getHabits(UUID userId){
        ArrayList<Habit> habits = habitRepository.findAllByUserId(userId);
        return habits.stream()
                .map(habitMapper::toResponseDTO)
                .toList();
    }

    /** Core create: saves and returns the entity. Does NOT evict caches — callers decide. */
    public Habit createHabitEntity(CreateHabitDTO createHabitDTO, UUID userId){
        User user = userRepository.findById(userId)
        .orElseThrow(() -> new UserNotFound("User not found"));

        XpByLevel actualBaseXp = xpByLevelRepository.findByLevel(createHabitDTO.experience().getLevel());
        XpByLevel nextLevelXp = xpByLevelRepository.findByLevel(createHabitDTO.experience().getLevel() + 1);

        ArrayList<Category> categories = new ArrayList<>();
        // Dedupe ids so a habit never gets the same category (and join row) twice.
        for(UUID categoryId : createHabitDTO.categoriesId().stream().distinct().toList()){
            categories.add(categoryService.getCategory(categoryId, userId));
        }

        Habit newHabit = habitMapper.toEntity(createHabitDTO, categories, actualBaseXp, nextLevelXp, user);

        try{
            return habitRepository.save(newHabit);
        }catch(Exception e){
            throw new BusinessException(ErrorKey.HABIT_CREATE_FAILED, "Error trying to create habit");
        }
    }

    public ResponseEntity<Map<String, String>> createHabit(CreateHabitDTO createHabitDTO, UUID userId){
        createHabitEntity(createHabitDTO, userId);
        userCacheEvictService.evictAllUserCaches(userId);
        return ResponseEntity.ok().body(Map.of("success", "Habit saved successfully"));
    }

    public ResponseEntity<Map<String, String>> editHabit(EditHabitDTO editHabitDTO, UUID userId){
        Habit habitToEdit = getHabit(editHabitDTO.habitId());
        if(!habitToEdit.getUser().getId().equals(userId)){
            throw new BusinessException(ErrorKey.HABIT_NOT_OWNED, "The habit is not from the user in context");
        }

        List<Category> categoriesEdit = new ArrayList<>();
        // Dedupe ids so a habit never gets the same category (and join row) twice.
        for(UUID categoryId : editHabitDTO.categoriesId().stream().distinct().toList()){
            categoriesEdit.add(categoryService.getCategory(categoryId, userId));
        }

        habitMapper.updateEntity(habitToEdit, editHabitDTO, categoriesEdit);
        try{
            habitRepository.save(habitToEdit);
            userCacheEvictService.evictAllUserCaches(userId);
            return ResponseEntity.ok().body(Map.of("success", "Habit edited successfully"));
        }catch(Exception e){
            throw new BusinessException(ErrorKey.HABIT_EDIT_FAILED, "Error trying to edit habit");
        }
    }

    /**
     * R8/KTD24 — deleting the habit deletes its day history with it.
     *
     * <p>The asymmetry is deliberate. Deleting a ROUTINE, or editing one to drop this habit,
     * leaves every row standing: {@code entity_check_day.owner_id} carries no foreign key
     * precisely so the database never makes that call, and {@code DiaryRoutineService} never
     * touches this table. Deleting the habit itself is different — it is a deliberate act on
     * an entity this method already refuses to remove while a routine still holds it, so
     * there is nobody left the history could belong to.
     *
     * <p>{@code @Transactional} is load-bearing rather than decorative here:
     * {@code deleteAllByOwner} is a bulk {@code @Modifying} query with no transaction of its
     * own and throws {@code TransactionRequiredException} without one. It also makes the pair
     * atomic — a delete that fails at flush rolls the history back with it.
     */
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
            int removedDays = entityCheckDayRepository.deleteAllByOwner(CheckDayOwnerType.HABIT, habitId);
            xpDayRecorder.forget(XpDayOwnerType.HABIT, habitId);
            log.info("Removed {} check-day rows for habit {}", removedDays, habitId);
            habitRepository.delete(habitToDelete);
            userCacheEvictService.evictAllUserCaches(userId);
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

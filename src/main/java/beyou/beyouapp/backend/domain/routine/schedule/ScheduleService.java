package beyou.beyouapp.backend.domain.routine.schedule;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.schedule.dto.ScheduleResponseDTO;
import beyou.beyouapp.backend.domain.routine.schedule.dto.UpdateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.exceptions.routine.ScheduleNotFoundException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
@Service
@AllArgsConstructor
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final DiaryRoutineService diaryRoutineService;
    private final UserCacheEvictService userCacheEvictService;


    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "schedules", key = "#userId")
    public List<ScheduleResponseDTO> findAll(UUID userId) {
        List<DiaryRoutine> routines = diaryRoutineService.getAllDiaryRoutinesModels(userId);
        return routines.stream()
                .map(DiaryRoutine::getSchedule)
                .filter(Objects::nonNull)
                .map(ScheduleResponseDTO::from)
                .toList();
    }

    public Optional<Schedule> findById(UUID id) {
        return scheduleRepository.findById(id);
    }

    /**
     * Schedules a routine, replacing whatever it was scheduled with.
     *
     * <p>The route is POST and the entity is new every time, but the operation is really
     * an upsert: nothing stops a client calling this on a routine that already has a
     * schedule, and the mobile sheet does exactly that every time someone changes the
     * days. The old row then has nothing pointing at it — {@code routines.schedule_id} is
     * a schedule's only inbound reference — and it is unreachable from that moment on,
     * along with its {@code schedule_days}. {@code V18} swept the ones that accumulated
     * before anyone noticed; this is what stops the next batch, since a cascade on the
     * routine's own removal does nothing for a row that was replaced rather than deleted.
     */
    @Transactional
    public Schedule create(CreateScheduleDTO scheduleDTO, UUID userId) {
        DiaryRoutine routine = diaryRoutineService.getDiaryRoutineModelById(scheduleDTO.routineId(), userId);
        Schedule schedule = new Schedule();

        checkAndReplaceScheduledRoutines(scheduleDTO.days(), userId);

        Schedule replaced = routine.getSchedule();

        log.info("SAVINg DAYS => {}", scheduleDTO.days());
        schedule.setDays(scheduleDTO.days());
        log.info("SCHEDULE AFTER DAYS => {}", schedule);
        Schedule scheduleSaved = scheduleRepository.save(schedule);
        log.info("ERROR NOT IN SAVING SCHEDULE");
        routine.setSchedule(scheduleSaved);

        diaryRoutineService.saveRoutine(routine);

        // After the routine points at the new one, never before: the delete has to come
        // second or it trips the foreign key that is still referencing the old row.
        if (replaced != null) {
            // flush() is the EntityManager's, not this repository's alone, so the
            // routine's new schedule_id is written first and the old row is free.
            scheduleRepository.flush();
            scheduleRepository.delete(replaced);
        }

        userCacheEvictService.evictAllUserCaches(userId);
        return schedule;
    }

    @Transactional
    public Schedule update(UpdateScheduleDTO updatedSchedule, UUID userId) {
        Schedule schedule = scheduleRepository.findById(updatedSchedule.scheduleId())
        .orElseThrow(() -> new ScheduleNotFoundException("Schedule not found by ID: " + updatedSchedule.scheduleId()));

        diaryRoutineService.getDiaryRoutineByScheduleId(schedule.getId(), userId);

        checkAndReplaceScheduledRoutines(updatedSchedule.days(), userId);

        schedule.setDays(updatedSchedule.days());

        Schedule updatedScheduleEntity = scheduleRepository.save(schedule);
        userCacheEvictService.evictAllUserCaches(userId);
        return updatedScheduleEntity;
    }

    @Transactional
    public void delete(UUID id, UUID userId) {

        if (!scheduleRepository.existsById(id)) {
            throw new ScheduleNotFoundException("Schedule not found with ID: " + id);
        }

        DiaryRoutine routine = diaryRoutineService.getDiaryRoutineByScheduleId(id, userId);

        routine.setSchedule(null);

        scheduleRepository.deleteById(id);
        userCacheEvictService.evictAllUserCaches(userId);
    }

    private void checkAndReplaceScheduledRoutines(Set<WeekDay> newDays, UUID userId){
        var routines = diaryRoutineService.getAllDiaryRoutinesModels(userId);

        for (var routine : routines){
            Schedule schedule = routine.getSchedule();
            if (schedule == null || schedule.getDays() == null) {
                continue;
            }

            boolean hasOverlap = schedule.getDays().stream().anyMatch(newDays::contains);

            if(!hasOverlap){
                continue;
            }
            
            log.info("[SERVICE] Removing days {} from routine {}", newDays, routine.getName());
            schedule.getDays().removeAll(newDays);
            scheduleRepository.save(schedule);
        }
    }
}

package beyou.beyouapp.backend.domain.routine.schedule;

import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
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

    public List<Schedule> findAll(UUID userId) {
        List<Schedule> schedules = new ArrayList<>();
        List<DiaryRoutine> routines = diaryRoutineService.getAllDiaryRoutinesModels(userId);

        routines.forEach((routine) -> {
            schedules.add(routine.getSchedule());
        });

        return schedules;
    }

    public Optional<Schedule> findById(UUID id) {
        return scheduleRepository.findById(id);
    }

    public Schedule create(CreateScheduleDTO scheduleDTO, UUID userId) {
        DiaryRoutine routine = diaryRoutineService.getDiaryRoutineModelById(scheduleDTO.routineId(), userId);
        Schedule schedule = new Schedule();

        checkAndReplaceScheduledRoutines(scheduleDTO.days(), userId);

        log.info("SAVINg DAYS => {}", scheduleDTO.days());
        schedule.setDays(scheduleDTO.days());
        log.info("SCHEDULE AFTER DAYS => {}", schedule);
        Schedule scheduleSaved = scheduleRepository.save(schedule);
        log.info("ERROR NOT IN SAVING SCHEDULE");
        routine.setSchedule(scheduleSaved);

        diaryRoutineService.saveRoutine(routine);

        return schedule;
    }

    public Schedule update(UpdateScheduleDTO updatedSchedule, UUID userId) {
        Schedule schedule = scheduleRepository.findById(updatedSchedule.scheduleId())
        .orElseThrow(() -> new ScheduleNotFoundException("Schedule not found by ID: " + updatedSchedule.scheduleId()));

        checkAndReplaceScheduledRoutines(updatedSchedule.days(), userId);

        schedule.setDays(updatedSchedule.days());

        return scheduleRepository.save(schedule);
    }

    public void delete(UUID id, UUID userId) {

        if (!scheduleRepository.existsById(id)) {
            throw new ScheduleNotFoundException("Schedule not found with ID: " + id);
        }

        DiaryRoutine routine = diaryRoutineService.getDiaryRoutineByScheduleId(id, userId);

        routine.setSchedule(null);

        scheduleRepository.deleteById(id);
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

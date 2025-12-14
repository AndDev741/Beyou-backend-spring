package beyou.beyouapp.backend.domain.routine.schedule;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.schedule.dto.UpdateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.exceptions.routine.ScheduleNotFoundException;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
@Service
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final DiaryRoutineService diaryRoutineService;

    @Autowired
    public ScheduleService(ScheduleRepository scheduleRepository, DiaryRoutineService diaryRoutineService) {
        this.scheduleRepository = scheduleRepository;
        this.diaryRoutineService = diaryRoutineService;
    }

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

        // if (hasScheduleConflict(routine.getId(), userId, scheduleDTO.days())) {
        //     log.info("Schedule already in use by another routine, removing");
        // }

        log.info("SAVINg DAYS => {}", scheduleDTO.days());
        schedule.setDays(scheduleDTO.days());
        log.info("SCHEDULE AFTER DAYS => {}", schedule);
        Schedule scheduleSaved = scheduleRepository.save(schedule);
        log.info("ERROR NOT IN SAVING SCHEDULE");
        routine.setSchedule(scheduleSaved);

        diaryRoutineService.updateSchedule(routine);

        return schedule;
    }

    public Schedule update(UpdateScheduleDTO updatedSchedule, UUID userId) {
        DiaryRoutine routine = diaryRoutineService.getDiaryRoutineModelById(updatedSchedule.routineId(), userId);
        Schedule schedule = scheduleRepository.findById(updatedSchedule.scheduleId())
        .orElseThrow(() -> new ScheduleNotFoundException("Schedule not found by ID: " + updatedSchedule.scheduleId()));

        // if (hasScheduleConflict(routine.getId(), userId, updatedSchedule.days())) {
        //     log.warn("Schedule already in use by another routine, removing");
        // }

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

    private boolean hasScheduleConflict(UUID actualRoutineInProccess, UUID userId, Set<String> newScheduleDays) {
        return diaryRoutineService.getAllDiaryRoutinesModels(userId)
                .stream()
                .anyMatch(r -> !r.getId().equals(actualRoutineInProccess) &&
                        !Collections.disjoint(r.getSchedule().getDays(), newScheduleDays));
    }
}

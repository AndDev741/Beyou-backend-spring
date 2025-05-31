package beyou.beyouapp.backend.controllers;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleService;
import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.schedule.dto.UpdateScheduleDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import jakarta.validation.Valid;

@RestController
@RequestMapping(value = "/schedule")
public class ScheduleController {
    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @GetMapping()
    public List<Schedule> getAllSchedules() {
        User authUser = authenticatedUser.getAuthenticatedUser();
        return scheduleService.findAll(authUser.getId());
    }

    @PostMapping
    public Schedule createSchedule(@RequestBody @Valid CreateScheduleDTO scheduleDTO){
        User authUser = authenticatedUser.getAuthenticatedUser();

        return scheduleService.create(scheduleDTO, authUser.getId());
    }

    @PutMapping
    public Schedule updateSchedule(@RequestBody UpdateScheduleDTO scheduleDTO){
        User authUser = authenticatedUser.getAuthenticatedUser();
        return scheduleService.update(scheduleDTO, authUser.getId());
    }

    @DeleteMapping(value = "/{scheduleId}")
    public ResponseEntity<Void> deleteSchedule(@PathVariable UUID scheduleId){
        User authUser = authenticatedUser.getAuthenticatedUser();

        scheduleService.delete(scheduleId, authUser.getId());

        return ResponseEntity.noContent().build();
    }
}

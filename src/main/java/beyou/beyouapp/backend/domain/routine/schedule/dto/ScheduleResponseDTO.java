package beyou.beyouapp.backend.domain.routine.schedule.dto;

import java.util.Set;
import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;

public record ScheduleResponseDTO(
    UUID id,
    Set<WeekDay> days
) {
    public static ScheduleResponseDTO from(Schedule schedule) {
        // Copy eagerly while the session is open: the lazy collection must not
        // leak into the DTO — streaming serializes on a thread with no session.
        return new ScheduleResponseDTO(schedule.getId(), Set.copyOf(schedule.getDays()));
    }
}

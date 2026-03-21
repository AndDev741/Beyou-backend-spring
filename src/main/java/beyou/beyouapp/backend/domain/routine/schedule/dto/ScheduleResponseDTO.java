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
        return new ScheduleResponseDTO(schedule.getId(), schedule.getDays());
    }
}

package beyou.beyouapp.backend.domain.calendar;

import java.time.LocalTime;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskResponseDTO;

@Component
public class CalendarTaskMapper {

    /**
     * @param zone        the owner's timezone, which the reminder instant is computed in
     * @param defaultTime the owner's default reminder time, for a task with no time of its own
     */
    public CalendarTaskResponseDTO toResponseDTO(CalendarTask task, ZoneId zone, LocalTime defaultTime) {
        return new CalendarTaskResponseDTO(
                task.getId(),
                task.getTitle(),
                task.getNote(),
                task.getTaskDate(),
                task.getTaskTime(),
                task.getAdvanceMinutes(),
                task.isCompleted(),
                task.getCompletedAt(),
                task.getXpAwarded(),
                CalendarReminderTime.instantOf(
                        task.getTaskDate(), task.getTaskTime(), task.getAdvanceMinutes(), zone, defaultTime));
    }
}

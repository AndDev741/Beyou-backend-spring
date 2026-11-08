package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.checks.HabitGroupCheck;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;

/**
 * One habit inside a DAILY routine's section, as a client sends it.
 *
 * <p>{@code days} limits the habit to some of the days the routine runs on. On an update,
 * null keeps whatever the item already has and an empty set clears it: a client that has
 * never heard of the field (an older mobile build) sends the whole routine back without it,
 * and null meaning "clear" would wipe the user's choice on every edit it makes.
 */
public record HabitGroupDTO(UUID id, UUID habitId, LocalTime startTime, LocalTime endTime,
        List<HabitGroupCheck> habitGroupCheck, Set<WeekDay> days) {

}

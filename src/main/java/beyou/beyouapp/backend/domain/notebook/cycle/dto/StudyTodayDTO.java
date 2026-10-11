package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Today across every active cycle that has minutes today. Cycles resting today are only named.
 *
 * @param date        the owner's own day
 * @param blocksDone  blocks closed today
 * @param blocksTotal blocks closed today plus the entries still in today's slice
 */
public record StudyTodayDTO(LocalDate date, DayOfWeek weekday, int plannedMinutes, int studiedMinutes,
        int blocksDone, int blocksTotal, List<StudyTodayCycleDTO> cycles, List<Resting> restingCycles) {

    public record Resting(UUID cycleId, String name) {
    }
}

package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.List;
import java.util.UUID;

/**
 * One cycle on today's card.
 *
 * @param position    the 1-based position of the lap's first open block ("bloco 7 de 15"), 0 when
 *                    the cycle has no blocks
 * @param blocksInLap how many blocks the lap has
 */
public record StudyTodayCycleDTO(UUID cycleId, String name, String startTime, int dayMinutes, int studiedMinutes,
        int lapNumber, int position, int blocksInLap, List<TodayEntryDTO> entries) {
}

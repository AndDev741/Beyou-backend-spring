package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.cycle.BlockMode;

/**
 * A whole cycle: its configuration, the lap in progress block by block, and up to five closed laps,
 * newest first.
 */
public record StudyCycleDTO(UUID id, String name, BlockMode defaultMode, int maxBlockMinutes, int position,
        List<CycleDayDTO> days, List<CycleTopicViewDTO> topics, LapSummaryDTO lap, List<StudyBlockDTO> blocks,
        List<LapSummaryDTO> previousLaps) {
}

package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.cycle.LapBlockState;

/**
 * A logged session and where its minutes went.
 *
 * @param credited the first block the minutes landed on, or null when the topic is in no active
 *                 cycle or has no block in it (the session is kept for the history anyway)
 */
public record SessionResponseDTO(UUID id, Credited credited) {

    public record Credited(UUID cycleId, String cycleName, UUID blockId, int position, int minutesBefore,
            int minutesAfter, int plannedMinutes, LapBlockState state) {
    }
}

package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.UUID;

/** An active cycle on the list: where its lap stands and what today asks of it. */
public record StudyCycleSummaryDTO(UUID id, String name, int position, LapSummaryDTO lap, Today today) {

    /** @param startTime "HH:mm" or null */
    public record Today(int minutes, String startTime) {
    }
}

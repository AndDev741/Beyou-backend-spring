package beyou.beyouapp.backend.domain.briefing.dto;

import java.util.List;

import beyou.beyouapp.backend.domain.notebook.cycle.BlockMode;

/**
 * One study cycle with minutes on the briefed day, as the "Today" page lists it.
 *
 * @param startTime "HH:mm", or null when the day has no start time
 * @param minutes   the day's planned minutes for the cycle
 * @param entries   the first blocks of the day's slice, at most three
 */
public record StudyAhead(String cycleName, String startTime, int minutes, List<Entry> entries) {

    /**
     * @param title "Entender X" / "Understand X" (in the account's language) for a GOAL block with
     *              a concept, the topic's title otherwise
     */
    public record Entry(String title, BlockMode mode, int minutesToday) {
    }
}

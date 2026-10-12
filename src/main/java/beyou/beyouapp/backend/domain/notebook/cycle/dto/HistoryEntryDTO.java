package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.time.Instant;
import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.dto.PageRefDTO;

/**
 * One line of a day in the history, tagged by {@code kind}. Each kind is its own record so the
 * JSON carries exactly that kind's fields.
 */
public sealed interface HistoryEntryDTO permits HistoryEntryDTO.Focus, HistoryEntryDTO.Manual,
        HistoryEntryDTO.Understood, HistoryEntryDTO.LapClosed {

    String kind();

    Instant at();

    /** A finished pomodoro run on a page inside one of the cycle's subjects. */
    record Focus(String kind, Instant at, SubjectRefDTO topic, PageRefDTO page, int minutes) implements HistoryEntryDTO {
        public static Focus of(Instant at, SubjectRefDTO topic, PageRefDTO page, int minutes) {
            return new Focus("FOCUS", at, topic, page, minutes);
        }
    }

    /** A logged session. Every session can be deleted by its owner. */
    record Manual(String kind, Instant at, UUID sessionId, SubjectRefDTO topic, PageRefDTO page, int minutes,
            String note) implements HistoryEntryDTO {
        public static Manual of(Instant at, UUID sessionId, SubjectRefDTO topic, PageRefDTO page, int minutes,
                String note) {
            return new Manual("MANUAL", at, sessionId, topic, page, minutes, note);
        }
    }

    /** A GOAL block closed as understood: the concept and the minutes it took. */
    record Understood(String kind, Instant at, SubjectRefDTO topic, PageRefDTO page, int minutes)
            implements HistoryEntryDTO {
        public static Understood of(Instant at, SubjectRefDTO topic, PageRefDTO page, int minutes) {
            return new Understood("UNDERSTOOD", at, topic, page, minutes);
        }
    }

    record LapClosed(String kind, Instant at, int lapNumber) implements HistoryEntryDTO {
        public static LapClosed of(Instant at, int lapNumber) {
            return new LapClosed("LAP_CLOSED", at, lapNumber);
        }
    }
}

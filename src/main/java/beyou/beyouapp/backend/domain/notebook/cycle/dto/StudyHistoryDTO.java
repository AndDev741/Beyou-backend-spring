package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A cycle's study, day by day, for at most 31 days.
 *
 * @param topics the cycle's subjects, for the legend
 */
public record StudyHistoryDTO(UUID cycleId, LocalDate from, LocalDate to, List<SubjectRefDTO> topics, List<Day> days) {

    /**
     * @param plannedMinutes the week's minutes for that weekday, as the week is configured now
     * @param minutes        pomodoros plus logged sessions that day
     * @param entries        in time order
     */
    public record Day(LocalDate date, int plannedMinutes, int minutes, List<TopicMinutes> byTopic,
            List<HistoryEntryDTO> entries) {
    }

    public record TopicMinutes(UUID topicId, int minutes) {
    }
}

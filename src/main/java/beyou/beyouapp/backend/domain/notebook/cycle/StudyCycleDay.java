package beyou.beyouapp.backend.domain.notebook.cycle;

import java.io.Serializable;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One weekday of a cycle's week. All seven rows exist for every cycle, so a rest day is a row
 * with 0 minutes and the week's minutes are a plain sum, never a second number to keep in step.
 */
@Entity
@Table(name = "study_cycle_days")
@IdClass(StudyCycleDay.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class StudyCycleDay {

    @Id
    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DayOfWeek weekday;

    @Column(nullable = false)
    private int minutes;

    /** Optional. With it, the day's slice gets clock times. */
    @Column(name = "start_time")
    private LocalTime startTime;

    public StudyCycleDay(UUID cycleId, DayOfWeek weekday, int minutes, LocalTime startTime) {
        this.cycleId = cycleId;
        this.weekday = weekday;
        this.minutes = minutes;
        this.startTime = startTime;
    }

    /** The composite key: (cycle, weekday). */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID cycleId;
        private DayOfWeek weekday;
    }
}

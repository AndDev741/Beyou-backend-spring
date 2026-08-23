package beyou.beyouapp.backend.domain.routine.schedule;

import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "schedules")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
public class Schedule {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Lazy, so reading a list of routines fetched these one schedule at a time — a
     * round trip per routine to answer "which days". {@code @BatchSize} collapses that
     * to one query per 50 schedules, which for any real account is one.
     */
    @ElementCollection(targetClass = WeekDay.class)
    @BatchSize(size = 50)
    @Enumerated(EnumType.STRING)
    @CollectionTable(
        name = "schedule_days",
        joinColumns = @JoinColumn(name = "schedule_id"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"schedule_id", "days"})
    )
    @Column(name = "days", nullable = false, columnDefinition = "varchar(20)")
    private Set<WeekDay> days;

}


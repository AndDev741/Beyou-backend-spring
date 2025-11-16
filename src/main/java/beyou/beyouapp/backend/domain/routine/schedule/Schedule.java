package beyou.beyouapp.backend.domain.routine.schedule;

import java.util.Set;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "schedules")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Schedule {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ElementCollection(targetClass = WeekDay.class)
    @Enumerated(EnumType.STRING)
    @CollectionTable(
        name = "schedule_days",
        joinColumns = @JoinColumn(name = "schedule_id"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"schedule_id", "days"})
    )
    @Column(name = "days", nullable = false, columnDefinition = "varchar(20)")
    private Set<WeekDay> days;

}


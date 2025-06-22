package beyou.beyouapp.backend.domain.routine.schedule;

import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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

    @ElementCollection(targetClass = String.class)
    @CollectionTable(
        name = "schedule_days",
        joinColumns = @JoinColumn(name = "schedule_id"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"schedule_id", "day"})
    )
    @Column(name = "day", nullable = false)
    private Set<String> days;

}


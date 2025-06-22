package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Inheritance(strategy = InheritanceType.JOINED)
public abstract class ItemGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    private LocalTime startTime;

    @ManyToOne
    @JoinColumn(name = "routine_section_id")
    private RoutineSection routineSection;
}

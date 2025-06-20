package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.routine.checks.HabitGroupCheck;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class HabitGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "habit_id", nullable = false)
    private Habit habit;

    private LocalTime startTime;

    @ManyToOne
    @JoinColumn(name = "routine_section_id")
    private RoutineSection routineSection;

    @OneToMany(mappedBy = "habitGroup", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<HabitGroupCheck> habitGroupChecks;
}
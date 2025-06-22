package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.List;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.routine.checks.HabitGroupCheck;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class HabitGroup extends ItemGroup {

    @ManyToOne
    @JoinColumn(name = "habit_id", nullable = false)
    private Habit habit;

    @OneToMany(mappedBy = "habitGroup", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<HabitGroupCheck> habitGroupChecks;
}
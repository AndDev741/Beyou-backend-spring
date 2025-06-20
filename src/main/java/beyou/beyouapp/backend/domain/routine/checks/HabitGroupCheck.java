package beyou.beyouapp.backend.domain.routine.checks;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.HabitGroup;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class HabitGroupCheck extends BaseCheck {

    @ManyToOne
    @JoinColumn(name = "habit_group_id", nullable = false)
    private HabitGroup habitGroup;
}
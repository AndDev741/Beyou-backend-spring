package beyou.beyouapp.backend.domain.routine.checks;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.TaskGroup;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class TaskGroupCheck extends BaseCheck {

    @ManyToOne
    @JoinColumn(name = "task_group_id", nullable = false)
    private TaskGroup taskGroup;
}
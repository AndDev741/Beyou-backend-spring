package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.routine.checks.TaskGroupCheck;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class TaskGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    private LocalTime startTime;

    @ManyToOne
    @JoinColumn(name = "routine_section_id")
    private RoutineSection routineSection;
    
    @OneToMany(mappedBy = "taskGroup", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TaskGroupCheck> taskGroupChecks;
}
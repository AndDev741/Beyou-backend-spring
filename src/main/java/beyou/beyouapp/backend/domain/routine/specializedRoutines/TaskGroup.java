package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.routine.checks.TaskGroupCheck;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class TaskGroup extends ItemGroup {

    @ManyToOne
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;
    
    @OneToMany(mappedBy = "taskGroup", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TaskGroupCheck> taskGroupChecks;
}
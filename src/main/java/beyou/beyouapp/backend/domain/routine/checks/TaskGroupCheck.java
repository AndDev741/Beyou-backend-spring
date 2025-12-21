package beyou.beyouapp.backend.domain.routine.checks;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import com.fasterxml.jackson.annotation.JsonIgnore;

import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class TaskGroupCheck extends BaseCheck {

    @ManyToOne
    @JsonIgnore
    @JoinColumn(name = "task_group_id", nullable = false)
    @ToString.Exclude
    private TaskGroup taskGroup;
}
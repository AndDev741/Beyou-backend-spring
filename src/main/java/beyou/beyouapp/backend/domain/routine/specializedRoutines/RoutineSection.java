package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.Routine;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class RoutineSection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String iconId;

    private LocalTime startTime;

    private LocalTime endTime;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TaskGroup> taskGroups;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    private List<HabitGroup> habitGroups;

    @ManyToOne
    @JoinColumn(name = "routine_id")
    private Routine routine;
}
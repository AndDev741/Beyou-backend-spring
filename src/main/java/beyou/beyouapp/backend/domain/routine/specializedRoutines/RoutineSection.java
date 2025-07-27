package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.Routine;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
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
    private List<TaskGroup> taskGroups = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    private List<HabitGroup> habitGroups = new ArrayList<>();

    @ManyToOne
    @JoinColumn(name = "routine_id")
    @ToString.Exclude
    private Routine routine;
}
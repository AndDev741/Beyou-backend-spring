package beyou.beyouapp.backend.domain.routine.snapshot;

import beyou.beyouapp.backend.domain.routine.Routine;
import beyou.beyouapp.backend.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
    name = "routine_snapshot",
    uniqueConstraints = @UniqueConstraint(columnNames = {"routine_id", "snapshot_date"}),
    indexes = {
        @Index(name = "idx_snapshot_user_routine_date", columnList = "user_id, routine_id, snapshot_date"),
        @Index(name = "idx_snapshot_routine_date", columnList = "routine_id, snapshot_date")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class RoutineSnapshot {
    @Id @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "routine_id", nullable = false)
    private Routine routine;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(nullable = false)
    private String routineName;

    private String routineIconId;

    @Column(columnDefinition = "text", nullable = false)
    private String structureJson;

    @Column(nullable = false)
    private boolean completed = false;

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SnapshotCheck> checks = new ArrayList<>();
}

package beyou.beyouapp.backend.domain.routine.snapshot;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "snapshot_check", indexes = { @Index(name = "idx_snapshot_check_snapshot", columnList = "snapshot_id") })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class SnapshotCheck {
    @Id @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private RoutineSnapshot snapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SnapshotItemType itemType;

    @Column(nullable = false) private String itemName;
    private String itemIconId;
    @Column(nullable = false) private String sectionName;
    private UUID originalItemId;
    private UUID originalGroupId;
    @Column(nullable = false) private int difficulty;
    @Column(nullable = false) private int importance;
    @Column(nullable = false) private boolean checked = false;
    @Column(nullable = false) private boolean skipped = false;
    private LocalTime checkTime;
    @Column(nullable = false) private double xpGenerated = 0.0;
}

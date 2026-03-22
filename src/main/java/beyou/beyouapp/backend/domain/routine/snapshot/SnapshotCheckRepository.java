package beyou.beyouapp.backend.domain.routine.snapshot;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface SnapshotCheckRepository extends JpaRepository<SnapshotCheck, UUID> {
    List<SnapshotCheck> findAllBySnapshotId(UUID snapshotId);
}

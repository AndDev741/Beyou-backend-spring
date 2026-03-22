package beyou.beyouapp.backend.domain.routine.snapshot.dto;

import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotItemType;
import java.time.LocalTime;
import java.util.UUID;

public record SnapshotCheckResponseDTO(
    UUID id, SnapshotItemType itemType, String itemName, String itemIconId,
    String sectionName, UUID originalGroupId, int difficulty, int importance,
    boolean checked, boolean skipped, LocalTime checkTime, double xpGenerated
) {}

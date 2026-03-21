package beyou.beyouapp.backend.domain.routine.snapshot.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SnapshotCheckRequestDTO(@NotNull UUID snapshotId, @NotNull UUID snapshotCheckId) {}

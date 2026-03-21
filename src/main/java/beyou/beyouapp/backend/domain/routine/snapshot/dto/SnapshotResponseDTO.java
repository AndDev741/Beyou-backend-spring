package beyou.beyouapp.backend.domain.routine.snapshot.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SnapshotResponseDTO(
    UUID id, LocalDate snapshotDate, String routineName, String routineIconId,
    boolean completed, @JsonRawValue String structure, List<SnapshotCheckResponseDTO> checks
) {}

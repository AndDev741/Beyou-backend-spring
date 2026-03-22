package beyou.beyouapp.backend.domain.routine.snapshot.dto;

import java.time.LocalDate;
import java.util.List;

public record SnapshotMonthResponseDTO(List<LocalDate> dates) {}

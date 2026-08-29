package beyou.beyouapp.backend.domain.focus.dto;

import java.time.LocalDate;
import java.util.List;

/** Everything the Focus Mode wrote on one day: what ran, and the small things done alongside. */
public record FocusDayResponseDTO(
    LocalDate date,
    List<FocusCycleResponseDTO> cycles,
    List<FocusMicroTaskResponseDTO> microTasks
) {}

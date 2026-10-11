package beyou.beyouapp.backend.domain.calendar.dto;

import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;

/**
 * The answer to a completion toggle: the task as it now stands, and the XP numbers the client
 * repaints from, the same {@link RefreshUiDTO} a goal completion returns.
 */
public record CalendarTaskCompletionDTO(
    CalendarTaskResponseDTO task,
    RefreshUiDTO refreshUi
) {}

package beyou.beyouapp.backend.domain.focus.dto;

import java.time.Instant;
import java.util.UUID;

import beyou.beyouapp.backend.domain.focus.CycleKind;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * One completed cycle, reported by the client the moment it ran out.
 *
 * <p>{@code notebookPageId} is optional too: the study notebook's "Focus 25 min" sends the page the
 * cycle was run on, so the node can show how long was spent on it. A page that is not the
 * caller's is refused like a foreign routine item.
 *
 * <p>{@code itemGroupId} is optional: a cycle can run with nothing selected. The date is NOT in the
 * request — the server resolves the owner's local day from their timezone, the same way every
 * check does, so a client with a wrong clock cannot file a cycle under the wrong day.
 */
public record RecordCycleRequestDTO(
    UUID itemGroupId,
    @NotNull CycleKind kind,
    @NotNull Instant startedAt,
    @NotNull Instant endedAt,
    @NotNull @Min(1) @Max(180) Integer minutes,
    UUID notebookPageId
) {}

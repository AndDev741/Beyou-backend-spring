package beyou.beyouapp.backend.domain.focus.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A micro-task for one routine item, today.
 *
 * <p>No date here either: the server resolves the owner's local day. {@code pinned} makes the NAME
 * a template that materialises on every item the person moves to (see {@code FocusService}).
 */
public record CreateMicroTaskRequestDTO(
    @NotNull UUID itemGroupId,
    @NotBlank @Size(max = 80) String name,
    boolean pinned
) {}

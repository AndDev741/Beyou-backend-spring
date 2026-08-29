package beyou.beyouapp.backend.domain.focus.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * The item's list, in the order it should now be in.
 *
 * <p>Whole-list rather than "move this row to index N": the client already holds the list it is
 * showing, and sending it back entire means the server never has to reconstruct what the person was
 * looking at. Two tabs dragging at once end with one of the two orders rather than an interleaving
 * neither of them asked for.
 *
 * <p>Ids the item does not own are ignored rather than refused, and rows left out keep their
 * relative order at the end. A list that grew in another tab between the read and the drop should
 * not turn a drag into an error.
 */
public record ReorderMicroTasksRequestDTO(
    @NotNull UUID itemGroupId,
    @NotEmpty List<UUID> ids) {
}

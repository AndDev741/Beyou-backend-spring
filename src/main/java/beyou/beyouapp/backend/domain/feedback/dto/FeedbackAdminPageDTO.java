package beyou.beyouapp.backend.domain.feedback.dto;

import java.util.List;

/**
 * A page of the admin inbox (R12).
 *
 * A hand-written envelope rather than a serialized Spring {@code Page}: the
 * framework's JSON shape is unstable across versions and carries a pile of
 * fields the console has no use for. {@code totalItems} counts the FILTERED
 * set, so paging controls stay honest under a status or category filter.
 */
public record FeedbackAdminPageDTO(
        List<FeedbackAdminItemDTO> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}

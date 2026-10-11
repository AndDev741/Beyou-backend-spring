package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.cycle.BlockMode;
import beyou.beyouapp.backend.domain.notebook.cycle.GoalSource;
import beyou.beyouapp.backend.domain.notebook.dto.PageRefDTO;

/** One block of the queue and its progress in the current lap. {@code position} is 1-based. */
public record StudyBlockDTO(UUID id, int position, PageRefDTO topic, BlockMode mode, int plannedMinutes,
        GoalSource goalSource, PageRefDTO page, boolean requireQuiz, String note, BlockProgressDTO progress) {
}

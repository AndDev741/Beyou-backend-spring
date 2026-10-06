package beyou.beyouapp.backend.domain.notebook.study.dto;

import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.study.StudyScope;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The setup screen's choices. A blank goal clears it. */
public record StudySetupRequestDTO(
        @Size(max = NotebookPage.MAX_STUDY_GOAL_LENGTH) String goal,
        @NotNull StudyScope scope) {
}

package beyou.beyouapp.backend.domain.notebook.ai.dto;

import beyou.beyouapp.backend.domain.notebook.study.StudyOutputKind;
import jakarta.validation.constraints.NotNull;

public record GenerateOutputRequestDTO(@NotNull StudyOutputKind kind) {
}

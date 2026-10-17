package beyou.beyouapp.backend.domain.notebook.diagram.dto;

import beyou.beyouapp.backend.domain.notebook.diagram.DiagramKind;
import beyou.beyouapp.backend.domain.notebook.diagram.DiagramSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /notebook/ai/pages/{id}/diagram}.
 *
 * @param prompt   what to draw, or with {@code base}, what to change
 * @param kind     the kind to draw; null lets the AI pick the one that fits
 * @param grounded true (the default, also when null) builds it from the page's notes and sources;
 *                 false draws from the model's own knowledge
 * @param base     a diagram to adjust; whatever the prompt does not touch is kept
 */
public record DiagramRequestDTO(
        @NotBlank @Size(max = 1000) String prompt,
        DiagramKind kind,
        Boolean grounded,
        DiagramSpec base) {
}

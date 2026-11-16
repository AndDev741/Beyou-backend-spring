package beyou.beyouapp.backend.domain.secondbrain.dto;

import beyou.beyouapp.backend.domain.secondbrain.SecondBrainFact;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainFactKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A fact typed on the page, new or edited. The text is stored trimmed. */
public record SecondBrainFactRequestDTO(
    @NotNull
    SecondBrainFactKind kind,

    @NotBlank
    @Size(max = SecondBrainFact.TEXT_MAX)
    String text
) {}

package beyou.beyouapp.backend.domain.secondbrain.dto;

import java.time.Instant;
import java.util.UUID;

import beyou.beyouapp.backend.domain.secondbrain.SecondBrainFactKind;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainFactOrigin;

/** One line of the profile, as the page and the export see it. */
public record SecondBrainFactDTO(
    UUID id,
    SecondBrainFactKind kind,
    String text,
    SecondBrainFactOrigin origin,
    Instant createdAt,
    Instant updatedAt
) {}

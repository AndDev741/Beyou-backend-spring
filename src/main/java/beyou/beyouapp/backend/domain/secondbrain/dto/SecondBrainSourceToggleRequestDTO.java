package beyou.beyouapp.backend.domain.secondbrain.dto;

import jakarta.validation.constraints.NotNull;

/** Boxed, so a body without the field is refused instead of read as false. */
public record SecondBrainSourceToggleRequestDTO(@NotNull Boolean enabled) {}

package beyou.beyouapp.backend.domain.secondbrain.dto;

import beyou.beyouapp.backend.domain.secondbrain.SecondBrainSource;

/** One switch with the value in effect, the default included. */
public record SecondBrainSourceDTO(SecondBrainSource source, boolean enabled) {}

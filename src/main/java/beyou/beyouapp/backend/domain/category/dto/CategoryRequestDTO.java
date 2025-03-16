package beyou.beyouapp.backend.domain.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CategoryRequestDTO (
                                  @NotEmpty @Size(min = 2, max = 256, message = "Category need a minimum of 2 characters")
                                  String name,
                                  @NotBlank
                                  String icon,
                                  String description,
                                  @NotNull
                                  int level,
                                  @NotNull
                                  int xp) {
}

package beyou.beyouapp.backend.domain.notebook.source.discovery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** "Find sources for me": what the person wants to learn from them, in their words. */
public record DiscoverSourcesRequestDTO(@NotBlank @Size(max = 500) String description) {
}

package beyou.beyouapp.backend.domain.notebook.source.discovery.dto;

import java.util.List;

/**
 * What "find sources for me" found. Nothing is stored: the person ticks the ones they want and
 * the client adds each as a link source.
 *
 * @param provider    TAVILY or GEMINI, the search that answered
 * @param skipped     results dropped because they did not open, pointed somewhere private, or
 *                    are already sources here
 */
public record DiscoveryResultDTO(String provider, List<DiscoveredSourceDTO> sources, int skipped) {
}

package beyou.beyouapp.backend.domain.notebook.source.discovery.dto;

/**
 * A page the search found and the server opened. {@code url} is where it really lands, after
 * redirects, so adding it as a link source fetches exactly what was shown.
 *
 * @param summary the search's excerpt or the sentence it supports, up to 240 characters
 */
public record DiscoveredSourceDTO(String title, String url, String domain, String summary) {
}

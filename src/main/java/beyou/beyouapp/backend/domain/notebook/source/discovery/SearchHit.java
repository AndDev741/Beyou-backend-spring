package beyou.beyouapp.backend.domain.notebook.source.discovery;

/** One web search result, before it is followed and checked. */
public record SearchHit(String title, String url, String snippet) {
}

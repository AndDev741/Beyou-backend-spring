package beyou.beyouapp.backend.domain.notebook.source;

/**
 * Where a source is in being read. Mirrored by {@code notebook_sources_status_check} in V34.
 *
 * <p>A source is usable from READY on. FAILED carries an error key on the row so the client can
 * say why in the reader's language.
 */
public enum NotebookSourceStatus {
    PENDING,
    READING,
    READY,
    FAILED
}

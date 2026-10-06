package beyou.beyouapp.backend.domain.notebook;

/**
 * Mirrored by {@code notebook_pages_kind_check} in V34. Adding a value here without adding it
 * there makes every write of the new kind fail at insert time.
 */
public enum NotebookPageKind {
    /** A root: the wrapper for everything studied under one subject. */
    TOPIC,
    /** Any page under a topic, on a board or not. */
    PAGE
}

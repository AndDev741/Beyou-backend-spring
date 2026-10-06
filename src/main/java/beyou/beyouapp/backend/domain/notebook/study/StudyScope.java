package beyou.beyouapp.backend.domain.notebook.study;

/**
 * Whose notes the study AI reads on a page. Mirrored by the CHECK constraint on
 * notebook_pages.study_scope (V36). Every scope keeps the pages above for context.
 */
public enum StudyScope {
    /** This page, and the pages above it. What the room read before the setup existed. */
    PAGE,
    /** This page and every page under it. */
    SUBTREE,
    /** Every page of the topic. */
    TOPIC
}

package beyou.beyouapp.backend.domain.notebook.ai.draft;

/** Mirrored by the CHECK constraint on notebook_roadmap_drafts.status (V35). */
public enum RoadmapDraftStatus {
    /** The model is writing it. The dialog and the home card show a timer. */
    DRAFTING,
    /** Drafted and waiting for the person to review it and create the topic. */
    READY,
    /** The model call failed or the server restarted mid-call; error_key says which. */
    FAILED
}

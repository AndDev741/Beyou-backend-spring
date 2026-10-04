package beyou.beyouapp.backend.domain.notebook.dto;

import beyou.beyouapp.backend.domain.notebook.NotebookStatus;

/**
 * What a client may ask a page's status to be. The three statuses, plus AUTO: "stop holding
 * this by hand and follow the nodes on my board".
 */
public enum StatusChoice {
    TO_STUDY,
    STUDYING,
    DONE,
    AUTO;

    public NotebookStatus toStatus() {
        return switch (this) {
            case TO_STUDY -> NotebookStatus.TO_STUDY;
            case STUDYING -> NotebookStatus.STUDYING;
            case DONE -> NotebookStatus.DONE;
            case AUTO -> throw new IllegalStateException("AUTO is not a status");
        };
    }
}

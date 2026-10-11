package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.cycle.BlockMode;
import beyou.beyouapp.backend.domain.notebook.cycle.GoalSource;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * Some fields of one block. A field left out of the JSON stays as it is; a field sent as null is
 * cleared (that is how a page hint or a note is removed).
 *
 * <p>A class with setters rather than a record because a record cannot tell "absent" from "null",
 * and here the two mean different things. Jackson calls a setter only for a property that is in
 * the body, so {@link #has} knows which ones were sent.
 */
@Getter
public class BlockPatchDTO {

    private BlockMode mode;
    @Min(5)
    @Max(600)
    private Integer plannedMinutes;
    private GoalSource goalSource;
    private UUID pageId;
    private Boolean requireQuiz;
    @Size(max = 200)
    private String note;

    @Getter(AccessLevel.NONE)
    private final Set<String> sent = new HashSet<>();

    public void setMode(BlockMode mode) {
        this.mode = mode;
        sent.add("mode");
    }

    public void setPlannedMinutes(Integer plannedMinutes) {
        this.plannedMinutes = plannedMinutes;
        sent.add("plannedMinutes");
    }

    public void setGoalSource(GoalSource goalSource) {
        this.goalSource = goalSource;
        sent.add("goalSource");
    }

    public void setPageId(UUID pageId) {
        this.pageId = pageId;
        sent.add("pageId");
    }

    public void setRequireQuiz(Boolean requireQuiz) {
        this.requireQuiz = requireQuiz;
        sent.add("requireQuiz");
    }

    public void setNote(String note) {
        this.note = note;
        sent.add("note");
    }

    /** Whether the field was in the body, null or not. */
    public boolean has(String field) {
        return sent.contains(field);
    }
}

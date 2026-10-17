package beyou.beyouapp.backend.domain.quicknote;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Where notes go when nobody chose a spot for them: the board's grid, and the two rules that use
 * it. Pure, no repository, no clock, so the arithmetic is unit-tested on its own.
 *
 * <p>The geometry is shared with the clients (mirrored in {@code @beyou/types/quickNote}). A note
 * is drawn {@link #NOTE_WIDTH} by {@link #NOTE_HEIGHT}; a grid cell adds {@link #GRID_GAP} to
 * each, so cell {@code (col, row)} has its top-left corner at {@code (col * 224, row * 184)}, with
 * {@link #GRID_COLUMNS} columns and as many rows as it takes.
 *
 * <p>Both rules live on the server so the web board, the phone board and the assistant put a new
 * note in the same place. Two clients computing it would drift the first time one of them
 * changed its grid.
 */
public final class QuickNotePlacement {

    public static final int NOTE_WIDTH = 200;
    public static final int NOTE_HEIGHT = 160;
    public static final int GRID_GAP = 24;
    public static final int GRID_COLUMNS = 5;
    public static final int CELL_WIDTH = NOTE_WIDTH + GRID_GAP;
    public static final int CELL_HEIGHT = NOTE_HEIGHT + GRID_GAP;

    /** A note's top-left corner, board units. */
    public record Spot(int x, int y) {
    }

    private QuickNotePlacement() {
    }

    /** The top-left corner of grid cell {@code index}, counted row-major from (0, 0). */
    public static Spot cell(int index) {
        return new Spot((index % GRID_COLUMNS) * CELL_WIDTH, (index / GRID_COLUMNS) * CELL_HEIGHT);
    }

    /**
     * The first cell, row-major from (0, 0), whose note-sized rectangle overlaps none of the
     * {@code occupied} notes' rectangles.
     *
     * <p>Overlap, not "a note sits in this cell": a note dragged half a cell to the right covers
     * two cells, and putting a new note on either would hide part of it. Edges that only touch do
     * not overlap. The search ends: every note blocks at most four cells, so the answer is within
     * the first {@code 4 * occupied.size() + 1}.
     *
     * @param occupied the top-left corners of the owner's active notes
     */
    public static Spot firstFreeCell(Collection<Spot> occupied) {
        for (int index = 0; ; index++) {
            Spot candidate = cell(index);
            boolean free = occupied.stream().noneMatch(note -> overlaps(candidate, note));
            if (free) {
                return candidate;
            }
        }
    }

    /**
     * "Tidy up": lays every given note out in the grid from (0, 0), grouped by colour in
     * {@link QuickNoteColor} order and oldest first inside a colour, and sets each one's z to its
     * place in that order. Returns the notes in their new order.
     *
     * <p>The id breaks a tie between two notes created in the same microsecond, so the same board
     * always tidies the same way.
     */
    public static List<QuickNote> tidy(Collection<QuickNote> active) {
        List<QuickNote> ordered = new ArrayList<>(active);
        ordered.sort(Comparator.comparing(QuickNote::getColor)
                .thenComparing(QuickNote::getCreatedAt)
                .thenComparing(QuickNote::getId));
        for (int index = 0; index < ordered.size(); index++) {
            Spot spot = cell(index);
            QuickNote note = ordered.get(index);
            note.setX(spot.x());
            note.setY(spot.y());
            note.setZ(index);
        }
        return ordered;
    }

    private static boolean overlaps(Spot a, Spot b) {
        return a.x() < b.x() + NOTE_WIDTH && b.x() < a.x() + NOTE_WIDTH
                && a.y() < b.y() + NOTE_HEIGHT && b.y() < a.y() + NOTE_HEIGHT;
    }
}

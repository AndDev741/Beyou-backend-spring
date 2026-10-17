package beyou.beyouapp.backend.unit.quicknote;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.quicknote.QuickNote;
import beyou.beyouapp.backend.domain.quicknote.QuickNoteColor;
import beyou.beyouapp.backend.domain.quicknote.QuickNotePlacement;
import beyou.beyouapp.backend.domain.quicknote.QuickNotePlacement.Spot;

/**
 * Where a note without a spot goes, and what "Tidy up" does to the board.
 *
 * <p>The fixtures are chosen so a plausible wrong rule gets a different answer. Notes on cells 0
 * and 2 make "the cell after the last note" and "one cell per note" both say cell 2 or 3 where the
 * answer is cell 1. A note half a cell off the grid blocks two cells, so a rule that snaps notes to
 * their nearest cell frees one of them. The tidy board mixes colours whose enum order, alphabetical
 * order and creation order all disagree.
 */
class QuickNotePlacementUnitTest {

    private static final Instant T0 = Instant.parse("2026-10-17T09:00:00Z");

    // ---- the grid ----

    @Test
    @DisplayName("cell (col, row) sits at (col * 224, row * 184), five to a row")
    void cellGeometryMatchesTheContract() {
        assertThat(QuickNotePlacement.cell(0)).isEqualTo(new Spot(0, 0));
        assertThat(QuickNotePlacement.cell(1)).isEqualTo(new Spot(224, 0));
        assertThat(QuickNotePlacement.cell(4)).isEqualTo(new Spot(896, 0));
        assertThat(QuickNotePlacement.cell(5)).as("the sixth cell starts the second row").isEqualTo(new Spot(0, 184));
        assertThat(QuickNotePlacement.cell(13)).isEqualTo(new Spot(672, 368));
    }

    // ---- first free cell ----

    @Test
    @DisplayName("an empty board starts at the origin")
    void emptyBoardStartsAtTheOrigin() {
        assertThat(QuickNotePlacement.firstFreeCell(List.of())).isEqualTo(new Spot(0, 0));
    }

    @Test
    @DisplayName("with cells 0 and 2 taken, the answer is cell 1, not the cell after the last note")
    void fillsTheGapBeforeGoingOn() {
        Spot spot = QuickNotePlacement.firstFreeCell(List.of(new Spot(0, 0), new Spot(448, 0)));

        assertThat(spot).isEqualTo(new Spot(224, 0));
    }

    @Test
    @DisplayName("a note half a cell off the grid blocks both cells it covers")
    void aNoteBetweenTwoCellsBlocksBoth() {
        // [112, 312) covers part of cell 0 [0, 200) and part of cell 1 [224, 424).
        Spot spot = QuickNotePlacement.firstFreeCell(List.of(new Spot(112, 0)));

        assertThat(spot).isEqualTo(new Spot(448, 0));
    }

    @Test
    @DisplayName("a note half a row down blocks the cell above it and the one below")
    void aNoteBetweenTwoRowsBlocksBoth() {
        // Row 0 full except cell 0, which a note half a row down overlaps along with cell 5.
        List<Spot> occupied = new ArrayList<>(List.of(
                new Spot(224, 0), new Spot(448, 0), new Spot(672, 0), new Spot(896, 0),
                new Spot(0, 92)));

        assertThat(QuickNotePlacement.firstFreeCell(occupied)).isEqualTo(new Spot(224, 184));
    }

    @Test
    @DisplayName("a full first row sends the note to the second row, five columns wide")
    void aFullRowWrapsAfterFiveColumns() {
        List<Spot> row = List.of(new Spot(0, 0), new Spot(224, 0), new Spot(448, 0),
                new Spot(672, 0), new Spot(896, 0));

        assertThat(QuickNotePlacement.firstFreeCell(row)).isEqualTo(new Spot(0, 184));
    }

    @Test
    @DisplayName("notes that only touch a cell's four edges leave it free")
    void touchingIsNotOverlapping() {
        // Cell 0 is [0, 200) x [0, 160). One note flush against each of its sides: right, below,
        // left and above. Any of the four comparisons turned inclusive blocks the cell.
        Spot spot = QuickNotePlacement.firstFreeCell(List.of(
                new Spot(200, 0), new Spot(0, 160), new Spot(-200, 0), new Spot(0, -160)));

        assertThat(spot).isEqualTo(new Spot(0, 0));
    }

    @Test
    @DisplayName("notes dragged far away or to negative coordinates do not block the grid")
    void notesOffTheGridDoNotBlockIt() {
        Spot spot = QuickNotePlacement.firstFreeCell(List.of(new Spot(5000, 5000), new Spot(-500, -400)));

        assertThat(spot).isEqualTo(new Spot(0, 0));
    }

    @Test
    @DisplayName("a note just above and left of the origin still overlaps cell 0")
    void aNoteReachingIntoCellZeroFromNegativeSpaceBlocksIt() {
        Spot spot = QuickNotePlacement.firstFreeCell(List.of(new Spot(-150, -100)));

        assertThat(spot).isEqualTo(new Spot(224, 0));
    }

    // ---- tidy ----

    @Test
    @DisplayName("tidy groups by colour in enum order, oldest first inside a colour, row-major from the origin")
    void tidyGroupsByColourThenAge() {
        // Enum order: YELLOW, PINK, BLUE, GREEN, PURPLE, ORANGE. Alphabetical would put BLUE
        // first and YELLOW last; creation order would put the YELLOW from t0 first and the ORANGE
        // from t1 second. Handed over shuffled, so the input order is not the answer either.
        QuickNote orange = note(QuickNoteColor.ORANGE, 1, 3000, 3000);
        QuickNote blue = note(QuickNoteColor.BLUE, 2, -40, 70);
        QuickNote pinkNewer = note(QuickNoteColor.PINK, 3, 10, 10);
        QuickNote yellowNewer = note(QuickNoteColor.YELLOW, 4, 0, 0);
        QuickNote yellowOlder = note(QuickNoteColor.YELLOW, 0, 500, 500);
        QuickNote green = note(QuickNoteColor.GREEN, 5, 90, 90);

        List<QuickNote> tidied = QuickNotePlacement.tidy(
                List.of(orange, blue, pinkNewer, yellowNewer, yellowOlder, green));

        assertThat(tidied).containsExactly(yellowOlder, yellowNewer, pinkNewer, blue, green, orange);
        assertThat(tidied).extracting(QuickNote::getX).containsExactly(0, 224, 448, 672, 896, 0);
        assertThat(tidied).extracting(QuickNote::getY).containsExactly(0, 0, 0, 0, 0, 184);
        assertThat(tidied).extracting(QuickNote::getZ).containsExactly(0, 1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("tidy breaks a tie in age by id, so the same board always tidies the same way")
    void tidyIsDeterministicOnATie() {
        QuickNote a = note(QuickNoteColor.BLUE, 7, 0, 0);
        QuickNote b = note(QuickNoteColor.BLUE, 7, 0, 0);
        a.setId(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
        b.setId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));

        assertThat(QuickNotePlacement.tidy(List.of(a, b))).containsExactly(b, a);
        assertThat(QuickNotePlacement.tidy(List.of(b, a))).containsExactly(b, a);
    }

    private static QuickNote note(QuickNoteColor color, int minutesAfterT0, int x, int y) {
        QuickNote note = new QuickNote();
        note.setId(UUID.randomUUID());
        note.setText(color + " idea");
        note.setColor(color);
        note.setX(x);
        note.setY(y);
        note.setZ(99);
        note.setCreatedAt(T0.plusSeconds(60L * minutesAfterT0));
        note.setUpdatedAt(note.getCreatedAt());
        return note;
    }
}

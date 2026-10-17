package beyou.beyouapp.backend.domain.quicknote;

/**
 * The six post-it colours. Names only: what each one looks like is the theme package's business,
 * per base (light or dark), so no hex lives on the server.
 *
 * <p>The declaration order is the tidy order: "Tidy up" groups the board by colour in exactly
 * this sequence. Reordering the constants reorders every tidied board.
 *
 * <p>Mirrored by the CHECK constraint in V42. Adding a colour here without adding it there makes
 * every write of the new colour fail at insert time.
 */
public enum QuickNoteColor {
    YELLOW,
    PINK,
    BLUE,
    GREEN,
    PURPLE,
    ORANGE
}

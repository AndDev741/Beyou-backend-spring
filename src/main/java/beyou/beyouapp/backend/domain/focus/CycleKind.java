package beyou.beyouapp.backend.domain.focus;

/**
 * The three cycles a Focus Mode timer can run.
 *
 * <p>Persisted as a string and mirrored by the {@code focus_cycles_kind_check} constraint in
 * {@code V27__focus_mode.sql}. <b>Adding a value here without adding it there makes every write of
 * the new kind fail</b>, at insert time, inside whatever request happened to trigger it.
 *
 * <p>The client's own names are camelCase (`shortBreak`); the mapping lives in the request DTO
 * rather than here, so the wire format can change without touching the column.
 */
public enum CycleKind {
    POMODORO,
    SHORT_BREAK,
    LONG_BREAK
}

package beyou.beyouapp.backend.domain.quicknote;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.quicknote.QuickNotePlacement.Spot;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteCreateRequestDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteEditRequestDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteLayoutRequestDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNotePositionDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteResponseDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteTaskConversionDTO;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;

/**
 * The Ideas board: quick notes, their spots, the archive, and turning a note into a task.
 *
 * <p>The rules that live here and nowhere else, because the web board, the phone board and the
 * assistant would drift on each:
 * <ol>
 *   <li><b>A note is only ever reachable by its owner.</b> Every lookup carries the owner's id
 *       ({@link QuickNoteRepository#findByIdAndUserId}, its locking twin, and the layout's batch
 *       read), so someone else's note and a missing one get the same 404
 *       {@code QUICK_NOTE_NOT_FOUND}.</li>
 *   <li><b>At most {@link QuickNote#MAX_ACTIVE} notes on the board.</b> Create and restore refuse
 *       the next one with 409 {@code QUICK_NOTE_LIMIT_REACHED}; archived notes do not count.</li>
 *   <li><b>A note without a spot goes to the first free grid cell, in front.</b>
 *       {@link QuickNotePlacement#firstFreeCell}, and a z one above the board's highest.</li>
 *   <li><b>A note becomes a task once.</b> The conversion creates the task and archives the note
 *       in one transaction, under a row lock, so a double submit makes one task and a 409.</li>
 * </ol>
 *
 * <p>Deliberately not cached, like mood and the notebook. Every read is one indexed query over at
 * most two hundred short rows, and nothing else in the product derives from these rows, so a cache
 * would buy nothing and add a staleness surface: a drag saved on the phone has to show on the web
 * at the next read. That is also why {@code config/CacheConfig} has no quick-note cache and no
 * write here evicts anything, except the conversion, which writes a task and tasks ARE cached.
 *
 * <p>No XP and no {@code RefreshUiDTO}: a note is an inbox entry, not progress. The task it turns
 * into earns XP the usual way, when it is checked in a routine.
 */
@Service
@RequiredArgsConstructor
public class QuickNoteService {

    /** How many archived notes one read returns, most recently archived first. */
    static final int ARCHIVED_LIST_MAX = 100;

    private final QuickNoteRepository noteRepository;
    private final QuickNoteMapper noteMapper;
    private final TaskService taskService;
    private final UserCacheEvictService userCacheEvictService;
    private final UserRepository userRepository;

    /**
     * Not injected, for the same reason as {@code CalendarService}'s: there is no {@code Clock}
     * bean. Non-final so Lombok leaves it out of the constructor; tests replace it to fix "now".
     */
    private Clock clock = Clock.systemDefaultZone();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** The board: active notes, back to front, oldest first among equals. */
    @Transactional(readOnly = true)
    public List<QuickNoteResponseDTO> listActive(User user) {
        return activeNotes(user).stream().map(noteMapper::toResponseDTO).toList();
    }

    /** The archive, most recently archived first, at most {@link #ARCHIVED_LIST_MAX}. */
    @Transactional(readOnly = true)
    public List<QuickNoteResponseDTO> listArchived(User user) {
        return noteRepository
                .findByUserIdAndArchivedAtIsNotNullOrderByArchivedAtDescIdAsc(user.getId(), Limit.of(ARCHIVED_LIST_MAX))
                .stream().map(noteMapper::toResponseDTO).toList();
    }

    /**
     * A new note, in front of every other. With both {@code x} and {@code y} it sits where it was
     * dropped; with either missing it takes the first free grid cell.
     *
     * @throws BusinessException {@link ErrorKey#QUICK_NOTE_LIMIT_REACHED} with the board full
     */
    @Transactional
    public QuickNoteResponseDTO create(User user, QuickNoteCreateRequestDTO request) {
        lockBoard(user);
        List<QuickNote> active = activeNotes(user);
        requireRoom(active);

        Spot spot = request.x() != null && request.y() != null
                ? new Spot(request.x(), request.y())
                : QuickNotePlacement.firstFreeCell(active.stream().map(QuickNoteService::spotOf).toList());

        Instant now = now();
        QuickNote note = new QuickNote();
        // A reference, not the principal: the principal was loaded by the security filter in
        // another session, and only its id is needed for the foreign key.
        note.setUser(userRepository.getReferenceById(user.getId()));
        note.setText(request.text().strip());
        note.setColor(request.color() == null ? QuickNoteColor.YELLOW : request.color());
        note.setX(spot.x());
        note.setY(spot.y());
        note.setZ(nextZ(active));
        note.setCreatedAt(now);
        note.setUpdatedAt(now);
        return noteMapper.toResponseDTO(noteRepository.save(note));
    }

    /** New words and colour. Works on an archived note too; it stays archived. */
    @Transactional
    public QuickNoteResponseDTO edit(User user, UUID noteId, QuickNoteEditRequestDTO request) {
        QuickNote note = owned(user, noteId);
        note.setText(request.text().strip());
        note.setColor(request.color());
        note.setUpdatedAt(now());
        return noteMapper.toResponseDTO(note);
    }

    /**
     * Saves the positions exactly as sent, all of them or none. Returns the whole board, in its new
     * stacking order, so the client redraws from one answer.
     *
     * @throws BusinessException {@link ErrorKey#QUICK_NOTE_NOT_FOUND} when any id is not one of
     *                           the caller's active notes; {@link ErrorKey#INVALID_REQUEST} when
     *                           an id appears twice
     */
    @Transactional
    public List<QuickNoteResponseDTO> saveLayout(User user, QuickNoteLayoutRequestDTO request) {
        Map<UUID, QuickNotePositionDTO> wanted = new LinkedHashMap<>();
        for (QuickNotePositionDTO position : request.positions()) {
            if (wanted.put(position.id(), position) != null) {
                throw new BusinessException(ErrorKey.INVALID_REQUEST,
                        "The same note appears twice in one layout");
            }
        }

        // One query for every id. Fewer rows back than ids asked for means at least one is someone
        // else's, archived or gone, and nothing has been written yet.
        List<QuickNote> notes = noteRepository.findByUserIdAndArchivedAtIsNullAndIdIn(user.getId(), wanted.keySet());
        if (notes.size() != wanted.size()) {
            throw notFound();
        }

        Instant now = now();
        for (QuickNote note : notes) {
            QuickNotePositionDTO target = wanted.get(note.getId());
            Position to = new Position(target.x(), target.y(), target.z());
            if (!Position.of(note).equals(to)) {
                note.setX(to.x());
                note.setY(to.y());
                note.setZ(to.z());
                note.setUpdatedAt(now);
            }
        }
        // The read flushes the moves first, so the order it returns is the new one.
        return listActive(user);
    }

    /**
     * "Tidy up": every active note into the grid, grouped by colour and oldest first inside a
     * colour (see {@link QuickNotePlacement#tidy}). Returns the board in its new order.
     */
    @Transactional
    public List<QuickNoteResponseDTO> tidy(User user) {
        lockBoard(user);
        List<QuickNote> active = activeNotes(user);
        Map<QuickNote, Position> before = new IdentityHashMap<>();
        active.forEach(note -> before.put(note, Position.of(note)));

        List<QuickNote> ordered = QuickNotePlacement.tidy(active);

        Instant now = now();
        for (QuickNote note : ordered) {
            if (!Position.of(note).equals(before.get(note))) {
                note.setUpdatedAt(now);
            }
        }
        return ordered.stream().map(noteMapper::toResponseDTO).toList();
    }

    /** Takes the note off the board. Idempotent: archiving twice keeps the first time. */
    @Transactional
    public QuickNoteResponseDTO archive(User user, UUID noteId) {
        QuickNote note = owned(user, noteId);
        if (!note.isArchived()) {
            Instant now = now();
            note.setArchivedAt(now);
            note.setUpdatedAt(now);
        }
        return noteMapper.toResponseDTO(note);
    }

    /**
     * Puts an archived note back where it was, in front of every other. Idempotent: restoring a
     * note already on the board changes nothing and is not refused by the limit, since it is
     * already counted.
     *
     * <p>The board lock comes before the lookup, so a double click reads the note after the first
     * restore committed and sees it active, instead of counting it against the limit twice.
     *
     * @throws BusinessException {@link ErrorKey#QUICK_NOTE_LIMIT_REACHED} with the board full
     */
    @Transactional
    public QuickNoteResponseDTO restore(User user, UUID noteId) {
        lockBoard(user);
        QuickNote note = owned(user, noteId);
        if (note.isArchived()) {
            List<QuickNote> active = activeNotes(user);
            requireRoom(active);
            note.setZ(nextZ(active));
            note.setArchivedAt(null);
            note.setUpdatedAt(now());
        }
        return noteMapper.toResponseDTO(note);
    }

    /** Deletes the note for good, on the board or archived. A task it became stays. */
    @Transactional
    public void delete(User user, UUID noteId) {
        noteRepository.delete(owned(user, noteId));
    }

    /**
     * "Make it a task": creates the task from the form the client prefilled with the note's words,
     * and archives the note pointing at it, in one transaction. A task that fails validation or
     * names someone else's category rolls the whole thing back, note untouched.
     *
     * <p>The note is read under a row lock. Without it, two submits a few milliseconds apart both
     * read "not converted yet" and both create a task; with it, the second waits for the first to
     * commit and then gets {@code QUICK_NOTE_ALREADY_CONVERTED}.
     *
     * <p>Tasks are cached per user and {@link TaskService#createTaskEntity} leaves eviction to its
     * caller, so this evicts, or the task list would miss the new task for half an hour.
     *
     * @throws BusinessException {@link ErrorKey#QUICK_NOTE_ALREADY_CONVERTED} when the note
     *                           already became a task
     */
    @Transactional
    public QuickNoteTaskConversionDTO convertToTask(User user, UUID noteId, CreateTaskRequestDTO request) {
        QuickNote note = noteRepository.lockByIdAndUserId(noteId, user.getId()).orElseThrow(this::notFound);
        if (note.getConvertedTaskId() != null) {
            throw new BusinessException(ErrorKey.QUICK_NOTE_ALREADY_CONVERTED, "This note is already a task");
        }

        Task task = taskService.createTaskEntity(request, user.getId());

        Instant now = now();
        if (!note.isArchived()) {
            note.setArchivedAt(now);
        }
        note.setConvertedTaskId(task.getId());
        note.setUpdatedAt(now);

        userCacheEvictService.evictAllUserCaches(user.getId());
        return new QuickNoteTaskConversionDTO(noteMapper.toResponseDTO(note), task.getId());
    }

    private List<QuickNote> activeNotes(User user) {
        return noteRepository.findByUserIdAndArchivedAtIsNullOrderByZAscCreatedAtAsc(user.getId());
    }

    private void lockBoard(User user) {
        noteRepository.lockBoard("quick-notes:" + user.getId());
    }

    private static void requireRoom(List<QuickNote> active) {
        if (active.size() >= QuickNote.MAX_ACTIVE) {
            throw new BusinessException(ErrorKey.QUICK_NOTE_LIMIT_REACHED,
                    "The board holds at most " + QuickNote.MAX_ACTIVE + " notes; archive one to make room");
        }
    }

    /**
     * One above the highest note on the board, or 0 on an empty one. Saturates rather than wraps:
     * a client may save any z it likes through the layout, and an overflow to a negative number
     * would send the newest note to the back.
     */
    private static int nextZ(List<QuickNote> active) {
        long highest = active.stream().mapToLong(QuickNote::getZ).max().orElse(-1);
        return (int) Math.min(highest + 1, Integer.MAX_VALUE);
    }

    private QuickNote owned(User user, UUID noteId) {
        return noteRepository.findByIdAndUserId(noteId, user.getId()).orElseThrow(this::notFound);
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorKey.QUICK_NOTE_NOT_FOUND, "Quick note not found");
    }

    private static Spot spotOf(QuickNote note) {
        return new Spot(note.getX(), note.getY());
    }

    /** Where a note sits and how high it is stacked, to tell a real move from a repeat. */
    private record Position(int x, int y, int z) {
        static Position of(QuickNote note) {
            return new Position(note.getX(), note.getY(), note.getZ());
        }
    }

    /**
     * Microseconds, which is what a timestamptz keeps, so the value in memory is the value stored
     * and the archive's newest-first order cannot be decided by a nanosecond the database dropped.
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}

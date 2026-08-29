package beyou.beyouapp.backend.domain.focus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.focus.dto.CreateMicroTaskRequestDTO;
import beyou.beyouapp.backend.domain.focus.dto.FocusCycleResponseDTO;
import beyou.beyouapp.backend.domain.focus.dto.FocusDayResponseDTO;
import beyou.beyouapp.backend.domain.focus.dto.FocusMicroTaskResponseDTO;
import beyou.beyouapp.backend.domain.focus.dto.RecordCycleRequestDTO;
import beyou.beyouapp.backend.domain.focus.dto.ReorderMicroTasksRequestDTO;
import beyou.beyouapp.backend.domain.routine.itemGroup.ItemGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.ItemGroupRepository;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * The Focus Mode's history: completed cycles, and the micro-tasks done alongside each routine item.
 *
 * <p>The one rule in here that is not obvious is how <b>pinned</b> works. The user's specification:
 * a micro-task belongs to a routine ITEM, and changing item does not carry the list over — unless
 * the micro-task is pinned, in which case changing item CREATES and links it to the new item too.
 *
 * <p>So a pinned name is a <em>template</em>. {@link #listMicroTasks} is what materialises it: on
 * every read for (day, item), any pinned name with no row for that pair gets one. The read is the
 * write, deliberately, because the moment the person selects an item is exactly when the list for
 * it should exist, and a separate "materialise" endpoint would be one more thing a client can forget
 * to call. The unique constraint on (user, day, item, name) is what makes repeating the read free.
 *
 * <p>Pinning is a property of the NAME, not of one row. Pin "stretch" on one item and it is pinned
 * everywhere, unpin it anywhere and it stops being a template everywhere. One flag per row would
 * have made the answer to "is this kept?" depend on which item you happened to be looking at.
 *
 * <p>Ownership is checked through the item's routine, the same path {@code CheckItemService} uses:
 * an item group belongs to a section, the section to a routine, the routine to a user.
 */
@Service
@RequiredArgsConstructor
public class FocusService {

    /** How many pinned names one list read may materialise. See {@link #materialisePinned}. */
    static final int MAX_PINNED_TEMPLATES = 50;

    private final FocusCycleRepository cycleRepository;
    private final FocusMicroTaskRepository microTaskRepository;
    private final ItemGroupRepository itemGroupRepository;

    // ---------------------------------------------------------------- cycles

    /**
     * One completed cycle, filed under the owner's local day.
     *
     * <p>Nothing here decides whether the cycle "counted" — the client only reports cycles that ran
     * out, and an abandoned one is never sent. The feature has no failure state, so there is nothing
     * to refuse.
     */
    @Transactional
    public FocusCycleResponseDTO recordCycle(User user, RecordCycleRequestDTO request) {
        if (!request.endedAt().isAfter(request.startedAt())) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "A cycle must end after it starts");
        }

        ItemGroup item = request.itemGroupId() == null ? null : ownedItem(user, request.itemGroupId());

        FocusCycle cycle = new FocusCycle();
        cycle.setUser(user);
        cycle.setCycleDate(UserDateResolver.today(user));
        cycle.setItemGroup(item);
        cycle.setKind(request.kind());
        cycle.setStartedAt(request.startedAt());
        cycle.setEndedAt(request.endedAt());
        cycle.setMinutes(request.minutes());
        return FocusCycleResponseDTO.from(cycleRepository.save(cycle));
    }

    // ----------------------------------------------------------- micro-tasks

    /**
     * One item's list for today, with every pinned template materialised first.
     *
     * <p>This is the read that is also a write. See the class comment for why.
     */
    @Transactional
    public List<FocusMicroTaskResponseDTO> listMicroTasks(User user, UUID itemGroupId) {
        ItemGroup item = ownedItem(user, itemGroupId);
        LocalDate today = UserDateResolver.today(user);
        materialisePinned(user, today, item);
        return microTaskRepository.findForItem(user.getId(), today, item.getId()).stream()
            .map(FocusMicroTaskResponseDTO::from)
            .toList();
    }

    /**
     * A new micro-task on one item, today.
     *
     * <p>Idempotent on the name: asking twice for "stretch" on the same item returns the existing
     * row rather than tripping the unique constraint into a 500. That is also what lets a client
     * retry a request whose response it lost.
     */
    @Transactional
    public FocusMicroTaskResponseDTO addMicroTask(User user, CreateMicroTaskRequestDTO request) {
        ItemGroup item = ownedItem(user, request.itemGroupId());
        LocalDate today = UserDateResolver.today(user);
        String name = normalise(request.name());

        // One read serves both questions: is the name already here, and where does the end of the
        // list sit.
        List<FocusMicroTask> current = microTaskRepository.findForItem(user.getId(), today, item.getId());
        FocusMicroTask existing = current.stream()
            .filter(t -> t.getName().equals(name))
            .findFirst()
            .orElse(null);
        if (existing != null) {
            if (request.pinned() && !existing.isPinned()) {
                setPinnedByName(user, name, true);
                existing.setPinned(true);
            }
            return FocusMicroTaskResponseDTO.from(existing);
        }

        FocusMicroTask task = newTask(user, today, item, name, request.pinned(), endOf(current));
        FocusMicroTask saved = microTaskRepository.save(task);
        if (request.pinned()) {
            // A name pinned on creation is a template from this moment, so the rows that already
            // exist for it elsewhere today follow suit.
            setPinnedByName(user, name, true);
        }
        return FocusMicroTaskResponseDTO.from(saved);
    }

    /** Ticked, or un-ticked. */
    @Transactional
    public FocusMicroTaskResponseDTO toggleMicroTask(User user, UUID id) {
        FocusMicroTask task = ownedTask(user, id);
        task.setDoneAt(task.getDoneAt() == null ? Instant.now() : null);
        return FocusMicroTaskResponseDTO.from(task);
    }

    /**
     * Keep this name for next time, or stop keeping it — on every row that carries the name.
     *
     * <p>See the class comment: pinning is a property of the name, so the answer to "is this kept?"
     * cannot depend on which item you are looking at.
     */
    @Transactional
    public FocusMicroTaskResponseDTO setPinned(User user, UUID id, boolean pinned) {
        FocusMicroTask task = ownedTask(user, id);
        setPinnedByName(user, task.getName(), pinned);
        task.setPinned(pinned);
        return FocusMicroTaskResponseDTO.from(task);
    }

    /**
     * The item's list, in the order the person dropped it into.
     *
     * <p>Takes the whole list rather than one move, and rewrites every position from it. Ids that
     * do not belong to this item are dropped, and rows the payload never mentions keep their
     * relative order AFTER the ones it does — a list that grew in another tab between the read and
     * the drop should not lose rows or turn a drag into an error.
     *
     * <p>Ownership is checked once on the item, not once per id: every row of this (user, day, item)
     * is by definition the user's, and the ids are only used to sort what the query already returned.
     */
    @Transactional
    public List<FocusMicroTaskResponseDTO> reorderMicroTasks(User user, ReorderMicroTasksRequestDTO request) {
        ItemGroup item = ownedItem(user, request.itemGroupId());
        LocalDate today = UserDateResolver.today(user);

        Map<UUID, FocusMicroTask> byId = new LinkedHashMap<>();
        for (FocusMicroTask task : microTaskRepository.findForItem(user.getId(), today, item.getId())) {
            byId.put(task.getId(), task);
        }

        List<FocusMicroTask> ordered = new ArrayList<>();
        for (UUID id : request.ids()) {
            FocusMicroTask task = byId.remove(id);
            if (task != null) ordered.add(task);
        }
        // Whatever the client did not name, in the order the query returned it.
        ordered.addAll(byId.values());

        for (int index = 0; index < ordered.size(); index++) {
            ordered.get(index).setOrderIndex(index);
        }
        return ordered.stream().map(FocusMicroTaskResponseDTO::from).toList();
    }

    /**
     * Remove the row — and, if it was pinned, stop keeping the name.
     *
     * <p>Without the second half a deleted pinned row came straight back on the next list read,
     * because the name was still a template everywhere else and {@link #listMicroTasks}
     * materialised it again. It vanished, then reappeared, with nothing to explain why. Deleting a
     * kept thing is the clearest "stop keeping this" a person can express, so it is read as that.
     * Rows already materialised on other items stay: they are real rows on real items, they just
     * stop being pinned.
     */
    @Transactional
    public void deleteMicroTask(User user, UUID id) {
        FocusMicroTask task = ownedTask(user, id);
        if (task.isPinned()) {
            setPinnedByName(user, task.getName(), false);
        }
        microTaskRepository.delete(task);
    }

    // ------------------------------------------------------------------- day

    /**
     * Everything the Focus Mode wrote on one day.
     *
     * <p>Read-only, and it materialises NOTHING: this is the history view, and a template should
     * appear on an item only once the person actually arrived at that item.
     */
    @Transactional(readOnly = true)
    public FocusDayResponseDTO getDay(User user, LocalDate date) {
        return new FocusDayResponseDTO(
            date,
            cycleRepository.findDay(user.getId(), date).stream().map(FocusCycleResponseDTO::from).toList(),
            microTaskRepository.findDay(user.getId(), date).stream().map(FocusMicroTaskResponseDTO::from).toList());
    }

    // -------------------------------------------------------------- internals

    /**
     * Every pinned name with no row for (today, item) gets one.
     *
     * <p>Reads the pinned set once and the item's existing names once, then inserts the difference.
     * The unique constraint would catch a race between two tabs, but the check avoids paying for the
     * constraint violation on the ordinary path.
     */
    private void materialisePinned(User user, LocalDate today, ItemGroup item) {
        // Bounded: this runs on a GET, under the read tier, and inserts one row per name. Fifty
        // most-recently pinned names is well past what anybody keeps on purpose, and it caps what a
        // single read can write. Mirrors the @Max(100) on docs search.
        List<String> pinned = microTaskRepository.findPinnedNames(user.getId(), PageRequest.of(0, MAX_PINNED_TEMPLATES));
        if (pinned.isEmpty()) return;

        List<FocusMicroTask> current = microTaskRepository.findForItem(user.getId(), today, item.getId());
        Set<String> present = new HashSet<>();
        for (FocusMicroTask t : current) {
            present.add(t.getName());
        }
        // Materialised templates land after whatever is already on the item, in the order the
        // pinned set comes back in.
        int next = endOf(current);
        for (String name : pinned) {
            if (!present.contains(name)) {
                microTaskRepository.save(newTask(user, today, item, name, true, next++));
            }
        }
    }

    private void setPinnedByName(User user, String name, boolean pinned) {
        for (FocusMicroTask t : microTaskRepository.findAllByUserIdAndName(user.getId(), name)) {
            t.setPinned(pinned);
        }
    }

    /** One past the highest position in a list, so a new row lands at the end of it. */
    private static int endOf(List<FocusMicroTask> list) {
        int next = 0;
        for (FocusMicroTask t : list) {
            next = Math.max(next, t.getOrderIndex() + 1);
        }
        return next;
    }

    private static FocusMicroTask newTask(
            User user, LocalDate day, ItemGroup item, String name, boolean pinned, int orderIndex) {
        FocusMicroTask task = new FocusMicroTask();
        task.setUser(user);
        task.setTaskDate(day);
        task.setItemGroup(item);
        task.setName(name);
        task.setPinned(pinned);
        task.setOrderIndex(orderIndex);
        task.setCreatedAt(Instant.now());
        return task;
    }

    private static String normalise(String name) {
        String trimmed = name.trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }

    /** The item group, or a refusal if it is missing or belongs to somebody else. */
    private ItemGroup ownedItem(User user, UUID itemGroupId) {
        ItemGroup item = itemGroupRepository.findWithOwner(itemGroupId)
            .orElseThrow(() -> new BusinessException(ErrorKey.ITEM_GROUP_REQUIRED, "Item group not found"));
        UUID ownerId = item.getRoutineSection().getRoutine().getUser().getId();
        if (!ownerId.equals(user.getId())) {
            throw new BusinessException(ErrorKey.ROUTINE_NOT_OWNED, "Item group belongs to another user");
        }
        return item;
    }

    private FocusMicroTask ownedTask(User user, UUID id) {
        FocusMicroTask task = microTaskRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorKey.INVALID_REQUEST, "Micro-task not found"));
        if (!task.getUser().getId().equals(user.getId())) {
            throw new BusinessException(ErrorKey.ROUTINE_NOT_OWNED, "Micro-task belongs to another user");
        }
        return task;
    }
}

package beyou.beyouapp.backend.domain.notebook.cycle;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.focus.FocusCycle;
import beyou.beyouapp.backend.domain.focus.FocusCycleRepository;
import beyou.beyouapp.backend.domain.notebook.NotebookOwnership;
import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookPageKind;
import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.NotebookStatus;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardNodeRepository;
import beyou.beyouapp.backend.domain.notebook.card.NotebookCardRepository;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.BlockPatchDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.BlockProgressDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.BlockRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleConfigRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleDayDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleTopicDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleTopicViewDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.LapSummaryDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.PlanRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.PlanResponseDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.SessionRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.SessionResponseDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyBlockDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyCycleDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyCycleSummaryDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyTodayCycleDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyTodayDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.TargetDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.TodayEntryDTO;
import beyou.beyouapp.backend.domain.notebook.study.NotebookStudyOutputRepository;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * Study cycles: the queue, its laps, and the one place every rule about them is applied.
 *
 * <p>Two things move a lap, and both come through here:
 * <ul>
 *   <li><b>Minutes</b>, through {@link #credit}: a finished pomodoro on a page of the topic
 *       ({@code FocusService.recordCycle}) or a manual session ({@link #logSession}). Nothing else
 *       credits, and no XP is paid anywhere in this feature.</li>
 *   <li><b>Understanding</b>, through {@link #onPagesDone} (called by
 *       {@code NotebookProgressService} for every page that reaches DONE, whatever made it) and
 *       {@link #onQuizPassed} (called by {@code NotebookStudyService} on a first pass).</li>
 * </ul>
 * The arithmetic of both lives in {@link StudyLap}; this class loads the rows, locks the cycle,
 * asks the lap, and saves.
 *
 * <p><b>Locking.</b> Every write that moves a lap holds the cycle's row lock
 * ({@link StudyCycleRepository#lockById}, or {@link StudyCycleRepository#lockActive} for all of a
 * user's cycles, in id order). Two pomodoros finishing together would otherwise both read a block
 * at 50 of 70 minutes and both write their own total, or both close the same lap. The reads that
 * also write ({@link #today}, {@link #get}) take the same lock, so two screens opening at once
 * cannot both create the same lap row.
 *
 * <p><b>The read that writes.</b> A GOAL block whose source is NEXT gets its concept from the
 * roadmap when it is first worked on. {@link #today} and {@link #get} also fix it for the first
 * open GOAL block of each topic. That matters for a person who studies the concept the card
 * showed on the page screen, with no timer, and marks it done: the block must close as
 * understood, and it can only if the concept was written down before the page moved. Later blocks
 * of the same topic are only previewed, because the concept they get depends on what happens
 * first.
 *
 * <p>Not cached, like the rest of the notebook. The reads write (above), and every pomodoro and
 * every page status change elsewhere moves the numbers, so a cached answer would need evicting
 * from the focus, page, board and quiz paths; a handful of indexed reads per request is the
 * cheaper side of that trade.
 */
@Service
@RequiredArgsConstructor
public class StudyCycleService {

    static final int MAX_ACTIVE_CYCLES = 10;
    static final int MAX_TOPICS = 30;
    static final int MAX_BLOCKS = 60;
    static final int SESSION_DAYS_BACK = 30;
    static final int PREVIOUS_LAPS = 5;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final StudyCycleRepository cycleRepository;
    private final StudyCycleDayRepository dayRepository;
    private final StudyCycleTopicRepository topicRepository;
    private final StudyCycleBlockRepository blockRepository;
    private final StudyCycleLapBlockRepository lapBlockRepository;
    private final StudyCycleLapRepository lapRepository;
    private final StudySessionRepository sessionRepository;
    private final NotebookOwnership ownership;
    private final NotebookPageRepository pageRepository;
    private final NotebookBoardNodeRepository nodeRepository;
    private final NotebookStudyOutputRepository outputRepository;
    private final NotebookCardRepository cardRepository;
    private final FocusCycleRepository focusCycleRepository;

    // =================================================================== plan

    /** The planner's answer for a week and topics, stateless. See {@link StudyCyclePlanner}. */
    public PlanResponseDTO plan(PlanRequestDTO request) {
        Map<DayOfWeek, CycleDayDTO> days = week(request.days());
        uniqueTopics(request.topics());
        checkMaxBlock(request.maxBlockMinutes());
        return toDto(planFor(days, request.maxBlockMinutes(), request.topics()));
    }

    // ================================================================= config

    @Transactional
    public StudyCycleDTO create(User user, CycleConfigRequestDTO request) {
        String name = name(request.name());
        Map<DayOfWeek, CycleDayDTO> days = week(request.days());
        checkMaxBlock(request.maxBlockMinutes());
        if (request.defaultMode() == null) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "defaultMode is TIME or GOAL");
        }
        checkTopics(user, request.topics(), null);
        if (cycleRepository.countByUserIdAndArchivedAtIsNull(user.getId()) >= MAX_ACTIVE_CYCLES) {
            throw new BusinessException(ErrorKey.STUDY_CYCLE_LIMIT,
                    "At most " + MAX_ACTIVE_CYCLES + " active study cycles");
        }
        StudyCyclePlanner.Plan plan = planFor(days, request.maxBlockMinutes(), request.topics());
        checkBlockCount(plan.blocks().size());

        Instant now = Instant.now();
        StudyCycle cycle = new StudyCycle();
        cycle.setUser(user);
        cycle.setName(name);
        cycle.setDefaultMode(request.defaultMode());
        cycle.setMaxBlockMinutes(request.maxBlockMinutes());
        cycle.setLapStartedAt(now);
        cycle.setPosition(cycleRepository.findActive(user.getId()).stream()
                .mapToInt(StudyCycle::getPosition).max().orElse(0) + 1);
        cycle.setCreatedAt(now);
        cycle.setUpdatedAt(now);
        cycleRepository.save(cycle);

        for (CycleDayDTO day : days.values()) {
            dayRepository.save(new StudyCycleDay(cycle.getId(), day.weekday(), day.minutes(), time(day.startTime())));
        }
        int position = 1;
        for (CycleTopicDTO topic : request.topics()) {
            topicRepository.save(topicRow(cycle.getId(), topic, position++));
        }
        for (StudyCyclePlanner.BlockPlan planned : plan.blocks()) {
            blockRepository.save(newBlock(cycle, planned));
        }
        blockRepository.flush();
        return view(user, cycle, true);
    }

    /**
     * A change to the configuration. When the plan's inputs moved (the week's minutes, the block
     * size, the topics or their weights) the blocks are planned again and merged onto the old
     * list: the k-th block of a topic in the new plan keeps the id, the lap progress and the
     * per-block settings of the k-th block of that topic in the old list. Blocks with no match are
     * deleted with their progress; new ones get the cycle's default mode.
     *
     * <p>When only the name, the default mode or the start times changed, nothing is re-planned,
     * so an order the person arranged by hand survives a rename. The plan would come out the same
     * anyway; only the hand-made changes would be lost.
     */
    @Transactional
    public StudyCycleDTO update(User user, UUID cycleId, CycleConfigRequestDTO request) {
        StudyCycle cycle = lockEditable(user, cycleId);
        String name = name(request.name());
        Map<DayOfWeek, CycleDayDTO> days = week(request.days());
        checkMaxBlock(request.maxBlockMinutes());
        if (request.defaultMode() == null) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "defaultMode is TIME or GOAL");
        }
        checkTopics(user, request.topics(), cycle.getId());

        List<StudyCycleDay> oldDays = dayRepository.findByCycleId(cycle.getId());
        List<StudyCycleTopic> oldTopics = topicRepository.findByCycleIdOrderByPositionAsc(cycle.getId());
        boolean replan = cycle.getMaxBlockMinutes() != request.maxBlockMinutes()
                || weekMinutes(oldDays) != weekMinutes(days.values())
                || !sameTopics(oldTopics, request.topics());

        StudyCyclePlanner.Plan plan = planFor(days, request.maxBlockMinutes(), request.topics());
        if (replan) checkBlockCount(plan.blocks().size());

        Map<DayOfWeek, StudyCycleDay> dayRows = new EnumMap<>(DayOfWeek.class);
        oldDays.forEach(d -> dayRows.put(d.getWeekday(), d));
        for (CycleDayDTO day : days.values()) {
            StudyCycleDay row = dayRows.get(day.weekday());
            if (row == null) {
                dayRepository.save(new StudyCycleDay(cycle.getId(), day.weekday(), day.minutes(), time(day.startTime())));
            } else {
                row.setMinutes(day.minutes());
                row.setStartTime(time(day.startTime()));
            }
        }

        Map<UUID, StudyCycleTopic> topicRows = new HashMap<>();
        oldTopics.forEach(t -> topicRows.put(t.getTopicId(), t));
        int position = 1;
        for (CycleTopicDTO topic : request.topics()) {
            StudyCycleTopic row = topicRows.remove(topic.topicId());
            if (row == null) {
                topicRepository.save(topicRow(cycle.getId(), topic, position++));
            } else {
                row.setImportance(topic.importance());
                row.setMastery(topic.mastery());
                row.setMinutesOverride(topic.minutesOverride());
                row.setPosition(position++);
            }
        }
        // A topic taken out of the cycle is free for another one.
        topicRepository.deleteAll(topicRows.values());

        cycle.setName(name);
        cycle.setDefaultMode(request.defaultMode());
        cycle.setMaxBlockMinutes(request.maxBlockMinutes());
        Instant now = Instant.now();
        cycle.setUpdatedAt(now);

        if (replan) merge(cycle, plan);
        blockRepository.flush();
        settle(user, cycle, now);
        return view(user, cycle, true);
    }

    /**
     * The queue, replaced by the list given, in that order. Existing blocks named by id keep their
     * lap progress; blocks left out are deleted with theirs. A block cannot move to another topic:
     * its minutes were studied on this one.
     */
    @Transactional
    public StudyCycleDTO replaceBlocks(User user, UUID cycleId, List<BlockRequestDTO> requests) {
        StudyCycle cycle = lockEditable(user, cycleId);
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "A cycle needs at least one block");
        }
        checkBlockCount(requests.size());
        Set<UUID> topics = activeTopicIds(cycle.getId());
        Map<UUID, StudyCycleBlock> existing = new LinkedHashMap<>();
        blockRepository.findByCycleIdOrderByPositionAsc(cycle.getId()).forEach(b -> existing.put(b.getId(), b));

        Set<UUID> changedGoals = new HashSet<>();
        int position = 1;
        for (BlockRequestDTO request : requests) {
            if (!topics.contains(request.topicId())) {
                throw new BusinessException(ErrorKey.INVALID_REQUEST, "The block's topic is not in this cycle");
            }
            StudyCycleBlock block;
            if (request.id() != null) {
                block = existing.remove(request.id());
                if (block == null) {
                    throw new BusinessException(ErrorKey.INVALID_REQUEST,
                            "Block " + request.id() + " is not in this cycle, or is listed twice");
                }
                if (!block.getTopicId().equals(request.topicId())) {
                    throw new BusinessException(ErrorKey.INVALID_REQUEST, "A block cannot move to another topic");
                }
            } else {
                block = new StudyCycleBlock();
                block.setCycleId(cycle.getId());
                block.setTopicId(request.topicId());
            }
            block.setPosition(position++);
            if (apply(user, block, request.mode(), request.plannedMinutes(), request.goalSource(), request.pageId(),
                    Boolean.TRUE.equals(request.requireQuiz()), request.note()) && block.getId() != null) {
                changedGoals.add(block.getId());
            }
            blockRepository.save(block);
        }
        blockRepository.deleteAll(existing.values());
        blockRepository.flush();

        Instant now = Instant.now();
        cycle.setUpdatedAt(now);
        forgetTargets(cycle, changedGoals);
        settle(user, cycle, now);
        return view(user, cycle, true);
    }

    /** Some fields of one block. See {@link BlockPatchDTO} for absent versus null. */
    @Transactional
    public StudyCycleDTO patchBlock(User user, UUID cycleId, UUID blockId, BlockPatchDTO patch) {
        StudyCycle cycle = lockEditable(user, cycleId);
        StudyCycleBlock block = blockRepository.findById(blockId)
                .filter(b -> b.getCycleId().equals(cycle.getId()))
                .orElseThrow(() -> new BusinessException(ErrorKey.STUDY_CYCLE_NOT_FOUND, "Block not found in this cycle"));

        BlockMode mode = patch.has("mode") ? patch.getMode() : block.getMode();
        Integer planned = patch.has("plannedMinutes") ? patch.getPlannedMinutes() : Integer.valueOf(block.getPlannedMinutes());
        GoalSource source = patch.has("goalSource") ? patch.getGoalSource() : block.getGoalSource();
        UUID pageId = patch.has("pageId") ? patch.getPageId() : block.getPageId();
        boolean requireQuiz = patch.has("requireQuiz") ? Boolean.TRUE.equals(patch.getRequireQuiz()) : block.isRequireQuiz();
        String note = patch.has("note") ? patch.getNote() : block.getNote();
        if (mode == null || planned == null) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "mode and plannedMinutes cannot be cleared");
        }
        boolean goalChanged = apply(user, block, mode, planned, source, pageId, requireQuiz, note);

        Instant now = Instant.now();
        cycle.setUpdatedAt(now);
        blockRepository.flush();
        if (goalChanged) forgetTargets(cycle, Set.of(block.getId()));
        settle(user, cycle, now);
        return view(user, cycle, true);
    }

    /** Puts the cycle away: it keeps its history and frees its topics. No unarchive in v1. */
    @Transactional
    public void archive(User user, UUID cycleId) {
        StudyCycle cycle = lock(user, cycleId);
        if (cycle.archived()) return;
        Instant now = Instant.now();
        cycle.setArchivedAt(now);
        cycle.setUpdatedAt(now);
        topicRepository.findByCycleIdOrderByPositionAsc(cycle.getId()).forEach(t -> t.setActive(false));
    }

    /** The cycle and everything under it. Sessions stay, with no cycle, as the topic's history. */
    @Transactional
    public void delete(User user, UUID cycleId) {
        cycleRepository.delete(lock(user, cycleId));
    }

    // ================================================================= reads

    /** The active cycles, in display order. Reads only. */
    @Transactional(readOnly = true)
    public List<StudyCycleSummaryDTO> list(User user) {
        List<StudyCycle> cycles = cycleRepository.findActive(user.getId());
        if (cycles.isEmpty()) return List.of();
        Loaded loaded = load(cycles);
        DayOfWeek weekday = UserDateResolver.today(user).getDayOfWeek();
        List<StudyCycleSummaryDTO> out = new ArrayList<>();
        for (StudyCycle cycle : cycles) {
            StudyCycleDay day = loaded.day(cycle.getId(), weekday);
            out.add(new StudyCycleSummaryDTO(cycle.getId(), cycle.getName(), cycle.getPosition(),
                    currentLap(cycle, loaded.lap(cycle)),
                    new StudyCycleSummaryDTO.Today(day == null ? 0 : day.getMinutes(), hhmm(day == null ? null : day.getStartTime()))));
        }
        return out;
    }

    /** One cycle in full. Fixes the concept of each topic's first open GOAL block (class comment). */
    @Transactional
    public StudyCycleDTO get(User user, UUID cycleId) {
        StudyCycle cycle = lock(user, cycleId);
        return view(user, cycle, !cycle.archived());
    }

    /** Today's slice of every active cycle. Fixes concepts like {@link #get}. */
    @Transactional
    public StudyTodayDTO today(User user) {
        return buildToday(user, UserDateResolver.today(user), true);
    }

    /**
     * Today as the morning briefing reads it, for {@code date}: nothing is fixed or written, so it
     * runs inside the briefing's read-only transaction.
     */
    @Transactional(readOnly = true)
    public StudyTodayDTO todayPreview(User user, LocalDate date) {
        return buildToday(user, date, false);
    }

    /**
     * What a day held for the briefing's recap: minutes studied on the topics of the active cycles
     * (pomodoros and logged sessions, the same sum as today's card) and concepts understood.
     */
    @Transactional(readOnly = true)
    public DayRecap recap(User user, LocalDate date) {
        List<StudyCycle> cycles = cycleRepository.findActive(user.getId());
        if (cycles.isEmpty()) return new DayRecap(0, 0);
        List<UUID> ids = cycles.stream().map(StudyCycle::getId).toList();
        Set<UUID> topics = new HashSet<>();
        topicRepository.findByCycleIdInOrderByPositionAsc(ids).stream()
                .filter(StudyCycleTopic::isActive).forEach(t -> topics.add(t.getTopicId()));
        NotebookFacts facts = facts(user.getId());
        int minutes = studied(facts, topics, focusCycleRepository.findNotebookPomodoros(user.getId(), date, date),
                sessionRepository.findRange(user.getId(), date, date));
        ZoneId zone = UserDateResolver.zoneOf(user);
        int understood = (int) lapBlockRepository.findByCycleIdInAndClosedAtGreaterThanEqualAndClosedAtLessThan(ids,
                        date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
                .stream().filter(r -> r.getState() == LapBlockState.UNDERSTOOD).count();
        return new DayRecap(minutes, understood);
    }

    public record DayRecap(int minutes, int conceptsUnderstood) {
    }

    // ================================================================ credit

    /** Where a credit went. Null from {@link #credit} when it went nowhere. */
    public record CreditOutcome(UUID cycleId, String cycleName, int lapNumber, StudyLap.Credit credit) {
    }

    /**
     * Minutes studied on a topic go into the active cycle that holds it, at {@code at}. The walk
     * is {@link StudyLap#credit}; this finds the cycle, locks it, saves, and closes the lap when
     * every block is closed (a lap row is written, the lap number moves on, and the overflow of
     * the session that closed it stays in the closed lap).
     *
     * @return null when no active cycle holds the topic, or the topic has no block in it
     */
    @Transactional
    public CreditOutcome credit(User user, UUID topicId, int minutes, Instant at) {
        StudyCycleTopic holder = topicRepository.findByTopicIdAndActiveTrue(topicId).orElse(null);
        if (holder == null) return null;
        StudyCycle cycle = cycleRepository.lockById(holder.getCycleId()).orElse(null);
        if (cycle == null || cycle.archived() || !cycle.getUser().getId().equals(user.getId())) return null;

        StudyLap lap = currentLapOf(cycle);
        StudyLap.Credit credit = lap.credit(topicId, minutes, at, facts(user.getId()));
        if (credit == null) return null;
        int lapNumber = cycle.getLapNumber();
        lapBlockRepository.saveAll(lap.created());
        closeLapIfComplete(cycle, lap, at);
        return new CreditOutcome(cycle.getId(), cycle.getName(), lapNumber, credit);
    }

    /**
     * Pages reached DONE. Called by {@code NotebookProgressService} at the end of every status
     * change, with every page the change finished (the rollup's parents included), so no caller
     * has to remember it.
     */
    @Transactional
    public void onPagesDone(UUID userId, Collection<UUID> pageIds, Instant at) {
        if (pageIds.isEmpty()) return;
        List<StudyCycle> cycles = cycleRepository.lockActive(userId);
        if (cycles.isEmpty()) return;
        Set<UUID> done = new HashSet<>(pageIds);
        NotebookFacts facts = facts(userId);
        Loaded loaded = load(cycles);
        for (StudyCycle cycle : cycles) {
            StudyLap lap = loaded.lap(cycle);
            if (lap.pagesDone(done, at, facts::quizPassed)) {
                lapBlockRepository.saveAll(lap.created());
                closeLapIfComplete(cycle, lap, at);
            }
        }
    }

    /** A quiz on the page passed for the first time. Called by {@code NotebookStudyService}. */
    @Transactional
    public void onQuizPassed(UUID userId, UUID pageId, Instant at) {
        NotebookPage page = pageRepository.findById(pageId).orElse(null);
        if (page == null || page.getStatus() != NotebookStatus.DONE) return;
        List<StudyCycle> cycles = cycleRepository.lockActive(userId);
        if (cycles.isEmpty()) return;
        Loaded loaded = load(cycles);
        for (StudyCycle cycle : cycles) {
            StudyLap lap = loaded.lap(cycle);
            if (lap.quizPassed(pageId, at)) {
                lapBlockRepository.saveAll(lap.created());
                closeLapIfComplete(cycle, lap, at);
            }
        }
    }

    // ============================================================== sessions

    /**
     * A manual session. Credited now, into the current lap, whatever {@code studiedOn} says: the
     * date only places it in the history. The date must be the owner's today or one of the 30
     * days before it.
     */
    @Transactional
    public SessionResponseDTO logSession(User user, SessionRequestDTO request) {
        NotebookPage topic = ownership.topic(user.getId(), request.topicId());
        UUID pageId = request.pageId() == null ? null : pageOfTopic(user, request.pageId(), topic.getId());
        LocalDate today = UserDateResolver.today(user);
        if (request.studiedOn().isAfter(today) || request.studiedOn().isBefore(today.minusDays(SESSION_DAYS_BACK))) {
            throw new BusinessException(ErrorKey.STUDY_SESSION_DATE,
                    "A session is dated today or within the last " + SESSION_DAYS_BACK + " days");
        }

        Instant now = Instant.now();
        CreditOutcome outcome = credit(user, topic.getId(), request.minutes(), now);

        StudySession session = new StudySession();
        session.setUser(user);
        session.setTopicId(topic.getId());
        session.setPageId(pageId);
        session.setMinutes(request.minutes());
        session.setStudiedOn(request.studiedOn());
        session.setNote(note(request.note()));
        session.setCreatedAt(now);
        if (outcome != null) {
            session.setCycleId(outcome.cycleId());
            session.setLapNumber(outcome.lapNumber());
        }
        sessionRepository.save(session);

        SessionResponseDTO.Credited credited = outcome == null ? null : new SessionResponseDTO.Credited(
                outcome.cycleId(), outcome.cycleName(), outcome.credit().blockId(), outcome.credit().position(),
                outcome.credit().minutesBefore(), outcome.credit().minutesAfter(),
                outcome.credit().plannedMinutes(), outcome.credit().state());
        return new SessionResponseDTO(session.getId(), credited);
    }

    /**
     * Deletes a session. If its minutes went into the lap still in progress they are taken back
     * ({@link StudyLap#debit}); a session credited to a lap that has since closed is just deleted,
     * because a closed lap is history.
     */
    @Transactional
    public void deleteSession(User user, UUID sessionId) {
        StudySession session = sessionRepository.findById(sessionId)
                .filter(s -> s.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new BusinessException(ErrorKey.STUDY_SESSION_NOT_FOUND, "Session not found"));
        if (session.getCycleId() != null && session.getLapNumber() != null) {
            StudyCycle cycle = cycleRepository.lockById(session.getCycleId()).orElse(null);
            if (cycle != null && !cycle.archived() && cycle.getLapNumber() == session.getLapNumber()) {
                currentLapOf(cycle).debit(session.getTopicId(), session.getMinutes());
            }
        }
        sessionRepository.delete(session);
    }

    // ============================================================ ownership

    /** The cycle, or the not-found / not-owned refusal, the way notebook pages refuse. */
    StudyCycle owned(User user, UUID cycleId) {
        StudyCycle cycle = cycleRepository.findById(cycleId)
                .orElseThrow(() -> new BusinessException(ErrorKey.STUDY_CYCLE_NOT_FOUND, "Study cycle not found"));
        if (!cycle.getUser().getId().equals(user.getId())) {
            throw new BusinessException(ErrorKey.STUDY_CYCLE_NOT_OWNED, "Study cycle belongs to another user");
        }
        return cycle;
    }

    /**
     * {@link #owned}, read under the row lock. The lock is the first read of the row in the
     * transaction, so what comes back is current and nobody can move the lap until it ends.
     */
    private StudyCycle lock(User user, UUID cycleId) {
        StudyCycle cycle = cycleRepository.lockById(cycleId)
                .orElseThrow(() -> new BusinessException(ErrorKey.STUDY_CYCLE_NOT_FOUND, "Study cycle not found"));
        if (!cycle.getUser().getId().equals(user.getId())) {
            throw new BusinessException(ErrorKey.STUDY_CYCLE_NOT_OWNED, "Study cycle belongs to another user");
        }
        return cycle;
    }

    /** Locked, and still active: an archived cycle is history and takes no edits. */
    private StudyCycle lockEditable(User user, UUID cycleId) {
        StudyCycle cycle = lock(user, cycleId);
        if (cycle.archived()) {
            throw new BusinessException(ErrorKey.STUDY_CYCLE_NOT_FOUND, "The study cycle is archived");
        }
        return cycle;
    }

    // ============================================================ internals

    NotebookFacts facts(UUID userId) {
        return new NotebookFacts(userId, pageRepository, nodeRepository, outputRepository, cardRepository);
    }

    private StudyLap currentLapOf(StudyCycle cycle) {
        return new StudyLap(cycle.getId(), cycle.getLapNumber(),
                blockRepository.findByCycleIdOrderByPositionAsc(cycle.getId()),
                lapBlockRepository.findByCycleIdAndLapNumber(cycle.getId(), cycle.getLapNumber()));
    }

    /**
     * After an edit: closes the blocks whose planned minutes fell to or below what they hold, and
     * the lap when every block is closed (removing the last open block can do that too).
     */
    private void settle(User user, StudyCycle cycle, Instant at) {
        StudyLap lap = currentLapOf(cycle);
        lap.closeFull(at, facts(user.getId()));
        lapBlockRepository.saveAll(lap.created());
        closeLapIfComplete(cycle, lap, at);
    }

    /** Closes the lap when every block is: a frozen lap row, and the next lap starts at {@code at}. */
    private void closeLapIfComplete(StudyCycle cycle, StudyLap lap, Instant at) {
        if (!lap.complete()) return;
        StudyLap.Totals totals = lap.totals();
        StudyCycleLap closed = new StudyCycleLap();
        closed.setCycleId(cycle.getId());
        closed.setNumber(cycle.getLapNumber());
        closed.setStartedAt(cycle.getLapStartedAt());
        closed.setClosedAt(at);
        closed.setMinutes(totals.minutes());
        closed.setPlannedMinutes(totals.plannedMinutes());
        closed.setUnderstood(totals.understood());
        closed.setTurnsUsed(totals.turnsUsed());
        closed.setBlocks(totals.blocks());
        lapRepository.save(closed);
        cycle.setLapNumber(cycle.getLapNumber() + 1);
        cycle.setLapStartedAt(at);
        cycle.setUpdatedAt(at);
    }

    /**
     * A block whose goal changed (mode, source or page) aims somewhere new, so the concept its
     * open lap row had fixed no longer applies and is resolved again on the next read or credit.
     */
    private void forgetTargets(StudyCycle cycle, Set<UUID> blockIds) {
        if (blockIds.isEmpty()) return;
        for (StudyCycleLapBlock row : lapBlockRepository.findByCycleIdAndLapNumber(cycle.getId(), cycle.getLapNumber())) {
            if (blockIds.contains(row.getBlockId()) && row.getState() == LapBlockState.OPEN) {
                row.setTargetPageId(null);
            }
        }
    }

    /**
     * The k-th block of each topic in the new plan takes over the k-th block of that topic in the
     * old list. See {@link #update}.
     */
    private void merge(StudyCycle cycle, StudyCyclePlanner.Plan plan) {
        Map<UUID, List<StudyCycleBlock>> oldByTopic = new HashMap<>();
        for (StudyCycleBlock block : blockRepository.findByCycleIdOrderByPositionAsc(cycle.getId())) {
            oldByTopic.computeIfAbsent(block.getTopicId(), k -> new ArrayList<>()).add(block);
        }
        Map<UUID, Integer> taken = new HashMap<>();
        for (StudyCyclePlanner.BlockPlan planned : plan.blocks()) {
            int k = taken.merge(planned.topicId(), 1, Integer::sum) - 1;
            List<StudyCycleBlock> old = oldByTopic.getOrDefault(planned.topicId(), List.of());
            if (k < old.size()) {
                StudyCycleBlock block = old.get(k);
                block.setPosition(planned.position());
                block.setPlannedMinutes(planned.plannedMinutes());
            } else {
                blockRepository.save(newBlock(cycle, planned));
            }
        }
        List<StudyCycleBlock> unmatched = new ArrayList<>();
        oldByTopic.forEach((topic, old) -> {
            int kept = taken.getOrDefault(topic, 0);
            if (old.size() > kept) unmatched.addAll(old.subList(kept, old.size()));
        });
        blockRepository.deleteAll(unmatched);
    }

    private static StudyCycleBlock newBlock(StudyCycle cycle, StudyCyclePlanner.BlockPlan planned) {
        StudyCycleBlock block = new StudyCycleBlock();
        block.setCycleId(cycle.getId());
        block.setPosition(planned.position());
        block.setTopicId(planned.topicId());
        block.setPlannedMinutes(planned.plannedMinutes());
        block.setMode(cycle.getDefaultMode());
        block.setGoalSource(cycle.getDefaultMode() == BlockMode.GOAL ? GoalSource.NEXT : null);
        return block;
    }

    /**
     * Writes a block's settings, normalised: GOAL defaults to NEXT; NEXT carries no page; PAGE
     * needs one; TIME carries no source and no quiz, and may keep a page as a hint. Every page must
     * be the caller's and belong to the block's topic.
     *
     * @return whether what the block aims at changed (mode, source or page)
     */
    private boolean apply(User user, StudyCycleBlock block, BlockMode mode, int planned, GoalSource source,
            UUID pageId, boolean requireQuiz, String note) {
        if (planned < StudyCycleBlock.MIN_MINUTES || planned > StudyCycleBlock.MAX_MINUTES) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "plannedMinutes is 5..600");
        }
        GoalSource newSource = null;
        UUID newPage = pageId;
        boolean newQuiz = false;
        if (mode == BlockMode.GOAL) {
            newSource = source == null ? GoalSource.NEXT : source;
            if (newSource == GoalSource.NEXT) {
                newPage = null;
            } else if (newPage == null) {
                throw new BusinessException(ErrorKey.INVALID_REQUEST, "A GOAL block aimed at a page needs the page");
            }
            newQuiz = requireQuiz;
        }
        if (newPage != null) {
            pageOfTopic(user, newPage, block.getTopicId());
        }
        boolean changed = block.getMode() != mode || block.getGoalSource() != newSource
                || !Objects.equals(block.getPageId(), newPage);
        block.setMode(mode);
        block.setPlannedMinutes(planned);
        block.setGoalSource(newSource);
        block.setPageId(newPage);
        block.setRequireQuiz(newQuiz);
        block.setNote(note(note));
        return changed;
    }

    /** A page of the caller's, in the given topic (the topic itself allowed). */
    private UUID pageOfTopic(User user, UUID pageId, UUID topicId) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        if (!topicId.equals(page.rootId())) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "The page is not in that topic");
        }
        return page.getId();
    }

    /**
     * Every topic is a TOPIC page of the caller's, listed once, and in no other active cycle. The
     * refusal for a taken topic names the cycle that holds it.
     */
    private void checkTopics(User user, List<CycleTopicDTO> topics, UUID cycleId) {
        if (topics.isEmpty()) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "A cycle needs at least one topic");
        }
        if (topics.size() > MAX_TOPICS) {
            throw new BusinessException(ErrorKey.STUDY_CYCLE_LIMIT, "At most " + MAX_TOPICS + " topics per cycle");
        }
        uniqueTopics(topics);
        List<UUID> ids = topics.stream().map(CycleTopicDTO::topicId).toList();
        Map<UUID, NotebookPage> pages = new HashMap<>();
        pageRepository.findByIdIn(ids).forEach(p -> pages.put(p.getId(), p));
        for (UUID id : ids) {
            NotebookPage page = pages.get(id);
            if (page == null) {
                throw new BusinessException(ErrorKey.NOTEBOOK_PAGE_NOT_FOUND, "Notebook page not found");
            }
            if (!page.getUser().getId().equals(user.getId())) {
                throw new BusinessException(ErrorKey.NOTEBOOK_PAGE_NOT_OWNED, "Notebook page does not belong to the user");
            }
            if (page.getKind() != NotebookPageKind.TOPIC) {
                throw new BusinessException(ErrorKey.NOTEBOOK_TOPIC_REQUIRED, "Expected a topic");
            }
        }
        for (StudyCycleTopic taken : topicRepository.findByTopicIdInAndActiveTrue(ids)) {
            if (taken.getCycleId().equals(cycleId)) continue;
            StudyCycle other = cycleRepository.findById(taken.getCycleId()).orElse(null);
            Map<String, String> details = new LinkedHashMap<>();
            details.put("topicId", taken.getTopicId().toString());
            details.put("cycleId", taken.getCycleId().toString());
            details.put("cycleName", other == null ? "" : other.getName());
            throw new BusinessException(ErrorKey.STUDY_CYCLE_TOPIC_TAKEN,
                    "The topic is already in the study cycle \"" + details.get("cycleName") + "\"", details);
        }
    }

    /**
     * Each topic once, with its numbers in range. The request DTOs carry the same bounds as
     * annotations; they are checked again here because the assistant builds its requests without
     * going through a controller, and the database's CHECKs would only answer with a conflict.
     */
    private static void uniqueTopics(List<CycleTopicDTO> topics) {
        Set<UUID> seen = new HashSet<>();
        for (CycleTopicDTO topic : topics) {
            if (!seen.add(topic.topicId())) {
                throw new BusinessException(ErrorKey.INVALID_REQUEST, "A topic is listed twice");
            }
            if (topic.importance() < 1 || topic.importance() > 5 || topic.mastery() < 1 || topic.mastery() > 5) {
                throw new BusinessException(ErrorKey.INVALID_REQUEST, "importance and mastery are 1 to 5");
            }
            if (topic.minutesOverride() != null && (topic.minutesOverride() < 5 || topic.minutesOverride() > 600)) {
                throw new BusinessException(ErrorKey.INVALID_REQUEST, "A topic's own minutes are 5 to 600");
            }
        }
    }

    private static void checkMaxBlock(Integer maxBlockMinutes) {
        if (maxBlockMinutes == null || maxBlockMinutes < 15 || maxBlockMinutes > 240) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "The longest block is 15 to 240 minutes");
        }
    }

    private static void checkBlockCount(int blocks) {
        if (blocks > MAX_BLOCKS) {
            throw new BusinessException(ErrorKey.STUDY_CYCLE_LIMIT,
                    "At most " + MAX_BLOCKS + " blocks per cycle: raise the block size or lower the minutes");
        }
    }

    /** Exactly seven distinct weekdays, minutes in steps of 5. */
    private static Map<DayOfWeek, CycleDayDTO> week(List<CycleDayDTO> days) {
        Map<DayOfWeek, CycleDayDTO> week = new EnumMap<>(DayOfWeek.class);
        for (CycleDayDTO day : days) {
            if (week.put(day.weekday(), day) != null) {
                throw new BusinessException(ErrorKey.INVALID_REQUEST, "Each weekday appears once");
            }
            if (day.minutes() < 0 || day.minutes() > 720 || day.minutes() % 5 != 0) {
                throw new BusinessException(ErrorKey.INVALID_REQUEST, "Day minutes are 0 to 720, in steps of 5");
            }
        }
        if (week.size() != 7) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "The week has seven days");
        }
        return week;
    }

    private static String name(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > StudyCycle.MAX_NAME_LENGTH) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "The name is 1 to 80 characters");
        }
        return name;
    }

    private static String note(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String note = raw.strip();
        if (note.length() > StudyCycleBlock.MAX_NOTE_LENGTH) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "A note is at most 200 characters");
        }
        return note;
    }

    private static StudyCycleTopic topicRow(UUID cycleId, CycleTopicDTO topic, int position) {
        StudyCycleTopic row = new StudyCycleTopic();
        row.setCycleId(cycleId);
        row.setTopicId(topic.topicId());
        row.setImportance(topic.importance());
        row.setMastery(topic.mastery());
        row.setMinutesOverride(topic.minutesOverride());
        row.setPosition(position);
        return row;
    }

    private static boolean sameTopics(List<StudyCycleTopic> old, List<CycleTopicDTO> requested) {
        if (old.size() != requested.size()) return false;
        for (int i = 0; i < old.size(); i++) {
            StudyCycleTopic a = old.get(i);
            CycleTopicDTO b = requested.get(i);
            if (!a.getTopicId().equals(b.topicId()) || a.getImportance() != b.importance()
                    || a.getMastery() != b.mastery() || !Objects.equals(a.getMinutesOverride(), b.minutesOverride())) {
                return false;
            }
        }
        return true;
    }

    private static StudyCyclePlanner.Plan planFor(Map<DayOfWeek, CycleDayDTO> days, int maxBlock,
            List<CycleTopicDTO> topics) {
        List<StudyCyclePlanner.TopicInput> inputs = topics.stream()
                .map(t -> new StudyCyclePlanner.TopicInput(t.topicId(), t.importance(), t.mastery(), t.minutesOverride()))
                .toList();
        return StudyCyclePlanner.plan(weekMinutes(days.values()), maxBlock, inputs);
    }

    private static int weekMinutes(Collection<CycleDayDTO> days) {
        return days.stream().mapToInt(CycleDayDTO::minutes).sum();
    }

    private static int weekMinutes(List<StudyCycleDay> days) {
        return days.stream().mapToInt(StudyCycleDay::getMinutes).sum();
    }

    private static PlanResponseDTO toDto(StudyCyclePlanner.Plan plan) {
        return new PlanResponseDTO(plan.weekMinutes(), plan.plannedMinutes(), plan.lapDays(),
                plan.topics().stream().map(t -> new PlanResponseDTO.Topic(t.topicId(), t.weight(), t.share(),
                        t.suggestedMinutes(), t.minutes(), t.blocks().size())).toList(),
                plan.blocks().stream().map(b -> new PlanResponseDTO.Block(b.position(), b.topicId(), b.plannedMinutes()))
                        .toList());
    }

    private Set<UUID> activeTopicIds(UUID cycleId) {
        Set<UUID> ids = new HashSet<>();
        topicRepository.findByCycleIdOrderByPositionAsc(cycleId).stream()
                .filter(StudyCycleTopic::isActive).forEach(t -> ids.add(t.getTopicId()));
        return ids;
    }

    static LocalTime time(String hhmm) {
        return hhmm == null || hhmm.isBlank() ? null : LocalTime.parse(hhmm, HH_MM);
    }

    static String hhmm(LocalTime time) {
        return time == null ? null : time.format(HH_MM);
    }

    // ================================================================= views

    /** Everything about several cycles, one query per table. */
    private Loaded load(List<StudyCycle> cycles) {
        List<UUID> ids = cycles.stream().map(StudyCycle::getId).toList();
        Loaded loaded = new Loaded();
        dayRepository.findByCycleIdIn(ids).forEach(d -> loaded.days
                .computeIfAbsent(d.getCycleId(), k -> new EnumMap<>(DayOfWeek.class)).put(d.getWeekday(), d));
        topicRepository.findByCycleIdInOrderByPositionAsc(ids).forEach(t -> loaded.topics
                .computeIfAbsent(t.getCycleId(), k -> new ArrayList<>()).add(t));
        blockRepository.findByCycleIdInOrderByPositionAsc(ids).forEach(b -> loaded.blocks
                .computeIfAbsent(b.getCycleId(), k -> new ArrayList<>()).add(b));
        lapBlockRepository.findCurrentLaps(ids).forEach(r -> loaded.rows
                .computeIfAbsent(r.getCycleId(), k -> new ArrayList<>()).add(r));
        return loaded;
    }

    private static final class Loaded {
        final Map<UUID, Map<DayOfWeek, StudyCycleDay>> days = new HashMap<>();
        final Map<UUID, List<StudyCycleTopic>> topics = new HashMap<>();
        final Map<UUID, List<StudyCycleBlock>> blocks = new HashMap<>();
        final Map<UUID, List<StudyCycleLapBlock>> rows = new HashMap<>();
        private final Map<UUID, StudyLap> laps = new HashMap<>();

        StudyCycleDay day(UUID cycleId, DayOfWeek weekday) {
            return days.getOrDefault(cycleId, Map.of()).get(weekday);
        }

        List<StudyCycleTopic> topics(UUID cycleId) {
            return topics.getOrDefault(cycleId, List.of());
        }

        StudyLap lap(StudyCycle cycle) {
            return laps.computeIfAbsent(cycle.getId(), id -> new StudyLap(id, cycle.getLapNumber(),
                    blocks.getOrDefault(id, List.of()), rows.getOrDefault(id, List.of())));
        }
    }

    /**
     * Fixes the concept of the first open GOAL block of each topic, when the roadmap has one. See
     * the class comment for why a read does this.
     */
    private void resolveHeads(StudyLap lap, NotebookFacts facts) {
        Set<UUID> seen = new HashSet<>();
        for (StudyCycleBlock block : lap.blocks()) {
            if (!block.goal() || lap.state(block).closed() || !seen.add(block.getTopicId())) continue;
            StudyCycleLapBlock row = lap.rowOrNull(block);
            if (row != null && row.getTargetPageId() != null) continue;
            UUID target = facts.resolve(block);
            if (target != null) {
                lap.row(block).setTargetPageId(target);
            }
        }
        lapBlockRepository.saveAll(lap.created());
        lap.created().clear();
    }

    private StudyCycleDTO view(User user, StudyCycle cycle, boolean persist) {
        Loaded loaded = load(List.of(cycle));
        StudyLap lap = loaded.lap(cycle);
        NotebookFacts facts = facts(user.getId());
        if (persist) resolveHeads(lap, facts);

        List<CycleDayDTO> days = new ArrayList<>();
        for (DayOfWeek weekday : DayOfWeek.values()) {
            StudyCycleDay day = loaded.day(cycle.getId(), weekday);
            days.add(new CycleDayDTO(weekday, day == null ? 0 : day.getMinutes(), hhmm(day == null ? null : day.getStartTime())));
        }
        List<CycleTopicViewDTO> topics = loaded.topics(cycle.getId()).stream()
                .filter(t -> t.isActive() || cycle.archived())
                .map(t -> new CycleTopicViewDTO(t.getTopicId(), t.getImportance(), t.getMastery(),
                        t.getMinutesOverride(), facts.ref(t.getTopicId())))
                .toList();
        List<StudyBlockDTO> blocks = lap.blocks().stream().map(block -> {
            StudyCycleLapBlock row = lap.rowOrNull(block);
            Shown shown = shown(block, lap, facts, false);
            return new StudyBlockDTO(block.getId(), block.getPosition(), facts.ref(block.getTopicId()), block.getMode(),
                    block.getPlannedMinutes(), block.getGoalSource(), facts.ref(block.getPageId()), block.isRequireQuiz(),
                    block.getNote(), new BlockProgressDTO(cycle.getLapNumber(), lap.minutes(block), lap.state(block),
                            row == null ? null : row.getClosedAt(), shown.target(), shown.preview(), shown.quizPassed()));
        }).toList();
        List<LapSummaryDTO> previous = lapRepository.findByCycleIdOrderByNumberDesc(cycle.getId(),
                PageRequest.of(0, PREVIOUS_LAPS)).stream().map(StudyCycleService::closedLap).toList();
        return new StudyCycleDTO(cycle.getId(), cycle.getName(), cycle.getDefaultMode(), cycle.getMaxBlockMinutes(),
                cycle.getPosition(), days, topics, currentLap(cycle, lap), blocks, previous);
    }

    private static LapSummaryDTO currentLap(StudyCycle cycle, StudyLap lap) {
        StudyLap.Totals totals = lap.totals();
        return new LapSummaryDTO(cycle.getLapNumber(), cycle.getLapStartedAt(), null, totals.minutes(),
                totals.plannedMinutes(), totals.blocksClosed(), totals.blocks(), totals.understood(), totals.turnsUsed());
    }

    private static LapSummaryDTO closedLap(StudyCycleLap lap) {
        return new LapSummaryDTO(lap.getNumber(), lap.getStartedAt(), lap.getClosedAt(), lap.getMinutes(),
                lap.getPlannedMinutes(), lap.getBlocks(), lap.getBlocks(), lap.getUnderstood(), lap.getTurnsUsed());
    }

    /** What a block shows as its concept, whether that is fixed, and the quiz. */
    private record Shown(TargetDTO target, boolean preview, Boolean quizPassed) {
    }

    /**
     * The concept a block shows. A fixed one when its row has it; for an open block with none, the
     * chosen page (PAGE, certain) or what the roadmap says now (NEXT, a preview). A block of the
     * next lap has no row, so it always shows that way.
     */
    private static Shown shown(StudyCycleBlock block, StudyLap lap, NotebookFacts facts, boolean nextLap) {
        if (!block.goal()) return new Shown(null, false, null);
        StudyCycleLapBlock row = nextLap ? null : lap.rowOrNull(block);
        UUID target;
        boolean preview = false;
        if (row != null && row.getTargetPageId() != null) {
            target = row.getTargetPageId();
        } else if (!nextLap && lap.state(block).closed()) {
            target = null;
        } else if (block.getGoalSource() == GoalSource.PAGE) {
            target = facts.resolve(block);
        } else {
            target = facts.nextOf(block.getTopicId());
            preview = target != null;
        }
        Boolean quiz = block.isRequireQuiz() ? Boolean.valueOf(target != null && facts.quizPassed(target)) : null;
        return new Shown(target == null ? null : facts.target(target), preview, quiz);
    }

    private StudyTodayDTO buildToday(User user, LocalDate date, boolean persist) {
        DayOfWeek weekday = date.getDayOfWeek();
        List<StudyCycle> cycles = persist ? cycleRepository.lockActive(user.getId())
                : cycleRepository.findActive(user.getId());
        if (cycles.isEmpty()) {
            return new StudyTodayDTO(date, weekday, 0, 0, 0, 0, List.of(), List.of());
        }
        cycles = new ArrayList<>(cycles);
        cycles.sort(Comparator.comparingInt(StudyCycle::getPosition).thenComparing(StudyCycle::getCreatedAt));
        List<UUID> ids = cycles.stream().map(StudyCycle::getId).toList();
        Loaded loaded = load(cycles);
        NotebookFacts facts = facts(user.getId());

        ZoneId zone = UserDateResolver.zoneOf(user);
        Map<UUID, List<StudyCycleLapBlock>> closedToday = new HashMap<>();
        lapBlockRepository.findByCycleIdInAndClosedAtGreaterThanEqualAndClosedAtLessThan(ids,
                        date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
                .forEach(r -> closedToday.computeIfAbsent(r.getCycleId(), k -> new ArrayList<>()).add(r));
        List<FocusCycle> pomodoros = focusCycleRepository.findNotebookPomodoros(user.getId(), date, date);
        List<StudySession> sessions = sessionRepository.findRange(user.getId(), date, date);

        record Built(StudyCycle cycle, LocalTime start, StudyTodayCycleDTO dto) {
        }
        List<Built> built = new ArrayList<>();
        List<StudyTodayDTO.Resting> resting = new ArrayList<>();
        int planned = 0;
        int studiedTotal = 0;
        int done = 0;
        int total = 0;
        for (StudyCycle cycle : cycles) {
            StudyLap lap = loaded.lap(cycle);
            if (persist) resolveHeads(lap, facts);
            StudyCycleDay day = loaded.day(cycle.getId(), weekday);
            int dayMinutes = day == null ? 0 : day.getMinutes();
            if (dayMinutes == 0) {
                resting.add(new StudyTodayDTO.Resting(cycle.getId(), cycle.getName()));
                continue;
            }
            LocalTime start = day.getStartTime();
            Set<UUID> topics = new HashSet<>();
            loaded.topics(cycle.getId()).stream().filter(StudyCycleTopic::isActive).forEach(t -> topics.add(t.getTopicId()));
            int studied = studied(facts, topics, pomodoros, sessions);

            Map<UUID, StudyCycleBlock> blocksById = new HashMap<>();
            lap.blocks().forEach(b -> blocksById.put(b.getId(), b));
            List<TodayEntryDTO> entries = new ArrayList<>();
            List<StudyCycleLapBlock> closed = new ArrayList<>(closedToday.getOrDefault(cycle.getId(), List.of()));
            closed.sort(Comparator.comparing(StudyCycleLapBlock::getClosedAt));
            for (StudyCycleLapBlock row : closed) {
                StudyCycleBlock block = blocksById.get(row.getBlockId());
                if (block == null) continue;
                UUID target = block.goal() ? row.getTargetPageId() : null;
                Boolean quiz = block.isRequireQuiz() ? Boolean.valueOf(target != null && facts.quizPassed(target)) : null;
                entries.add(entry(block, facts, date, row.getMinutes(), 0, row.getState(), true, false, null, null,
                        new Shown(target == null ? null : facts.target(target), false, quiz)));
            }
            int doneHere = entries.size();
            for (StudyDaySlice.Entry slice : StudyDaySlice.slice(lap, dayMinutes - studied, start, studied)) {
                entries.add(entry(slice.block(), facts, date, slice.credited(), slice.minutesToday(), LapBlockState.OPEN,
                        false, slice.nextLap(), hhmm(slice.start()), hhmm(slice.end()),
                        shown(slice.block(), lap, facts, slice.nextLap())));
            }
            StudyCycleBlock current = lap.firstOpen();
            built.add(new Built(cycle, start, new StudyTodayCycleDTO(cycle.getId(), cycle.getName(), hhmm(start),
                    dayMinutes, studied, cycle.getLapNumber(), current == null ? 0 : current.getPosition(),
                    lap.blocks().size(), entries)));
            planned += dayMinutes;
            studiedTotal += studied;
            done += doneHere;
            total += entries.size();
        }
        // Cycles with a start time today come first, by that time; the rest keep their order.
        built.sort(Comparator.comparing((Built b) -> b.start() == null)
                .thenComparing(b -> b.start() == null ? LocalTime.MIN : b.start()));
        return new StudyTodayDTO(date, weekday, planned, studiedTotal, done, total,
                built.stream().map(Built::dto).toList(), resting);
    }

    private static TodayEntryDTO entry(StudyCycleBlock block, NotebookFacts facts, LocalDate day, int credited,
            int minutesToday, LapBlockState state, boolean doneToday, boolean nextLap, String start, String end,
            Shown shown) {
        return new TodayEntryDTO(block.getId(), block.getPosition(), facts.ref(block.getTopicId()), block.getMode(),
                block.getGoalSource(), facts.ref(block.getPageId()), block.getNote(), block.isRequireQuiz(),
                block.getPlannedMinutes(), credited, minutesToday, state, doneToday, nextLap, start, end,
                shown.target(), shown.preview(), shown.quizPassed(), facts.cardsDue(block.getTopicId(), day));
    }

    /** Pomodoros on pages of these topics plus sessions on them. */
    private static int studied(NotebookFacts facts, Set<UUID> topics, List<FocusCycle> pomodoros,
            List<StudySession> sessions) {
        int minutes = 0;
        for (FocusCycle cycle : pomodoros) {
            UUID root = facts.rootOf(cycle.getNotebookPageId());
            if (root != null && topics.contains(root)) minutes += cycle.getMinutes();
        }
        for (StudySession session : sessions) {
            if (topics.contains(session.getTopicId())) minutes += session.getMinutes();
        }
        return minutes;
    }
}

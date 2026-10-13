package beyou.beyouapp.backend.domain.aiAgent.notebook;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookPageKind;
import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.cycle.BlockMode;
import beyou.beyouapp.backend.domain.notebook.cycle.GoalSource;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyCycleService;
import beyou.beyouapp.backend.domain.notebook.cycle.StudySession;
import beyou.beyouapp.backend.domain.notebook.cycle.StudySessionRepository;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.BlockPatchDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleConfigRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleDayDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleTopicDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleTopicViewDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.LapSummaryDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.SessionRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.SessionResponseDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyBlockDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyCycleDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyCycleSummaryDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyTodayCycleDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyTodayDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.SubjectRefDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.TodayEntryDTO;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * What the assistant can do with study cycles, addressed the way a person talks about them:
 * "what do I study today", "make me a cycle with Constitutional Law and Portuguese, three hours
 * on weekdays", "log 45 minutes of Portuguese", "take Saturday out of Concurso", "that session
 * was 30 minutes, not 45", "delete the old cycle". Everything the cycle screens can do, the
 * assistant can do too.
 *
 * <p>Everything goes through {@link StudyCycleService}, so the planner, the subject rules and the
 * credit walk are the ones the screens use. What lives here is the translation from names and
 * loose values to ids and DTOs, and the refusals that keep a model from guessing: a subject name
 * that matches nothing lists the topics there are, and one that matches two pages names each
 * with the topic it lives in and asks which.
 *
 * <p>A subject is any page, a topic or an area inside one, so names are matched across every
 * page. Two areas called "Fundamentos" in two topics are the common case, not an edge one.
 *
 * <p>A cycle is named by its name, in any case, or its id, and only active cycles answer: an
 * archived one is history, which takes no edits, and nothing lists it any more.
 *
 * <p>Lenient where a near miss is obviously meant (weekdays in any case or in Portuguese, "7:00"
 * for 07:00, minutes rounded to the 5-minute steps the week uses), strict where a guess would
 * write the wrong thing.
 */
@Service
@RequiredArgsConstructor
public class StudyCycleAssistant {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final DateTimeFormatter LENIENT_TIME = DateTimeFormatter.ofPattern("H:mm[:ss]");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final StudyCycleService cycleService;
    private final NotebookPageRepository pageRepository;
    private final StudySessionRepository sessionRepository;

    /** How long back {@code listStudySessions} looks by default, and at most. */
    private static final int SESSION_DAYS = 7;
    private static final int MAX_SESSION_DAYS = 31;

    /** A day of the week for {@code createStudyCycle} and {@code editStudyCycle}. */
    public record DayInput(String weekday, Integer minutes, String startTime) {
    }

    /**
     * A subject for {@code createStudyCycle} and {@code editStudyCycle}: a topic or a page inside
     * one, by title or id.
     */
    public record TopicInput(String topic, Integer importance, Integer mastery) {
    }

    // ----------------------------------------------------------------- reads

    /** Today's slice per cycle, compact. */
    public Map<String, Object> today(User user) {
        StudyTodayDTO today = cycleService.today(user);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", today.date().toString());
        out.put("plannedMinutes", today.plannedMinutes());
        out.put("studiedMinutes", today.studiedMinutes());
        out.put("blocksDone", today.blocksDone());
        out.put("blocksTotal", today.blocksTotal());
        List<Map<String, Object>> cycles = new ArrayList<>();
        for (StudyTodayCycleDTO cycle : today.cycles()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cycle", cycle.name());
            row.put("startTime", cycle.startTime());
            row.put("minutesToday", cycle.dayMinutes());
            row.put("studiedToday", cycle.studiedMinutes());
            row.put("lap", cycle.lapNumber());
            row.put("currentBlock", cycle.position() + " of " + cycle.blocksInLap());
            row.put("blocks", cycle.entries().stream().map(StudyCycleAssistant::entry).toList());
            cycles.add(row);
        }
        out.put("cycles", cycles);
        out.put("restingToday", today.restingCycles().stream().map(StudyTodayDTO.Resting::name).toList());
        return out;
    }

    public List<Map<String, Object>> list(User user) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StudyCycleSummaryDTO cycle : cycleService.list(user)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", cycle.id());
            row.put("name", cycle.name());
            row.put("lap", cycle.lap().number());
            row.put("blocksClosed", cycle.lap().blocksClosed() + " of " + cycle.lap().blocks());
            row.put("minutesThisLap", cycle.lap().minutes() + " of " + cycle.lap().plannedMinutes());
            row.put("conceptsUnderstoodThisLap", cycle.lap().understood());
            row.put("minutesToday", cycle.today().minutes());
            row.put("startTimeToday", cycle.today().startTime());
            out.add(row);
        }
        return out;
    }

    /** One active cycle in full: the week, the subjects with their weights, every block of the lap. */
    public Map<String, Object> detail(User user, String cycleRef) {
        StudyCycleDTO cycle = cycleService.get(user, activeCycle(user, cycleRef).id());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", cycle.id());
        out.put("name", cycle.name());
        out.put("defaultMode", cycle.defaultMode());
        out.put("maxBlockMinutes", cycle.maxBlockMinutes());
        out.put("week", cycle.days().stream().map(StudyCycleAssistant::day).toList());
        out.put("subjects", cycle.topics().stream().map(StudyCycleAssistant::subjectRow).toList());
        LapSummaryDTO lap = cycle.lap();
        out.put("lap", lap.number());
        out.put("blocksClosed", lap.blocksClosed() + " of " + lap.blocks());
        out.put("minutesThisLap", lap.minutes() + " of " + lap.plannedMinutes());
        out.put("blocks", cycle.blocks().stream().map(StudyCycleAssistant::block).toList());
        return out;
    }

    /**
     * The sessions logged by hand over the last days, with their ids, which is what
     * {@code deleteStudySession} needs. Pomodoros are not in here: they are the timer's record and
     * the assistant cannot change them.
     */
    public Map<String, Object> sessions(User user, Integer days) {
        int span = days == null ? SESSION_DAYS : days;
        if (span < 1 || span > MAX_SESSION_DAYS) {
            throw new IllegalArgumentException("days is 1 to " + MAX_SESSION_DAYS);
        }
        LocalDate today = UserDateResolver.today(user);
        LocalDate from = today.minusDays(span - 1L);
        List<StudySession> sessions = sessionRepository.findRange(user.getId(), from, today);
        Set<UUID> pageIds = new HashSet<>();
        sessions.forEach(session -> {
            pageIds.add(session.getTopicId());
            if (session.getPageId() != null) pageIds.add(session.getPageId());
        });
        Function<UUID, String> label = labels(pageIds);
        Map<UUID, String> cycleNames = cycleService.list(user).stream()
                .collect(Collectors.toMap(StudyCycleSummaryDTO::id, StudyCycleSummaryDTO::name));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (StudySession session : sessions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", session.getId());
            row.put("date", session.getStudiedOn().toString());
            row.put("subject", label.apply(session.getTopicId()));
            if (session.getPageId() != null) row.put("page", label.apply(session.getPageId()));
            row.put("minutes", session.getMinutes());
            if (session.getNote() != null) row.put("note", session.getNote());
            if (session.getCycleId() != null) {
                row.put("cycle", cycleNames.getOrDefault(session.getCycleId(), "a cycle that was archived or deleted"));
            }
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", today.toString());
        out.put("sessions", rows);
        return out;
    }

    // ---------------------------------------------------------------- writes

    /** A new cycle, laid out by the same planner as the editor. */
    public Map<String, Object> create(User user, String name, List<DayInput> days, List<TopicInput> topics,
            Integer maxBlockMinutes, String defaultMode) {
        if (topics == null || topics.isEmpty()) {
            throw new IllegalArgumentException("Send at least one subject: a topic or a page inside one, by its exact "
                    + "title or its id");
        }
        List<CycleTopicDTO> topicDtos = new ArrayList<>();
        for (TopicInput topic : topics) {
            if (topic == null) continue;
            NotebookPage page = subject(user, topic.topic());
            topicDtos.add(new CycleTopicDTO(page.getId(), scale(topic.importance(), "importance"),
                    scale(topic.mastery(), "mastery"), null));
        }
        int maxBlock = maxBlock(maxBlockMinutes == null ? 90 : maxBlockMinutes);
        BlockMode mode = defaultMode == null || defaultMode.isBlank() ? BlockMode.GOAL : mode(defaultMode, "defaultMode");
        CycleConfigRequestDTO request = new CycleConfigRequestDTO(name, mode, maxBlock, week(days, null), topicDtos);
        StudyCycleDTO cycle = cycleService.create(user, request);
        return planned("Created the study cycle", cycle);
    }

    /**
     * Changes a cycle's configuration. Whatever is left out stays as it is. Days sent replace those
     * days only, and the subjects, when sent, are the whole new list, in order: a subject already in
     * the cycle keeps its weights unless new ones come with it. The service re-plans the blocks only
     * when the week's total, the longest block or the subjects moved, and then keeps each block's
     * progress where a block of the same subject survives.
     */
    public Map<String, Object> edit(User user, String cycleRef, String name, List<DayInput> days,
            List<TopicInput> topics, Integer maxBlockMinutes, String defaultMode) {
        StudyCycleDTO current = cycleService.get(user, activeCycle(user, cycleRef).id());
        Map<UUID, CycleTopicViewDTO> before = current.topics().stream()
                .collect(Collectors.toMap(CycleTopicViewDTO::topicId, t -> t));
        List<CycleTopicDTO> subjects = new ArrayList<>();
        if (topics == null || topics.isEmpty()) {
            current.topics().forEach(t -> subjects.add(new CycleTopicDTO(t.topicId(), t.importance(), t.mastery(),
                    t.minutesOverride())));
        } else {
            for (TopicInput topic : topics) {
                if (topic == null) continue;
                NotebookPage page = subject(user, topic.topic());
                CycleTopicViewDTO was = before.get(page.getId());
                subjects.add(new CycleTopicDTO(page.getId(),
                        scale(topic.importance(), was == null ? 3 : was.importance(), "importance"),
                        scale(topic.mastery(), was == null ? 3 : was.mastery(), "mastery"),
                        was == null ? null : was.minutesOverride()));
            }
        }
        CycleConfigRequestDTO request = new CycleConfigRequestDTO(
                name == null || name.isBlank() ? current.name() : name,
                defaultMode == null || defaultMode.isBlank() ? current.defaultMode() : mode(defaultMode, "defaultMode"),
                maxBlockMinutes == null ? current.maxBlockMinutes() : maxBlock(maxBlockMinutes),
                days == null || days.isEmpty() ? current.days() : week(days, current.days()),
                subjects);
        StudyCycleDTO cycle = cycleService.update(user, current.id(), request);
        return planned("Updated the study cycle", cycle);
    }

    /**
     * Some settings of one block, named by its number in the queue. What is left out stays as it
     * is. A concept makes the block a GOAL block: a page inside the block's subject, or NEXT for
     * whatever the roadmap says is next when the block's turn comes.
     */
    public Map<String, Object> editBlock(User user, String cycleRef, Integer number, String mode,
            Integer plannedMinutes, String concept, Boolean requireQuiz, String note) {
        StudyCycleDTO current = cycleService.get(user, activeCycle(user, cycleRef).id());
        StudyBlockDTO block = current.blocks().stream()
                .filter(b -> number != null && b.position() == number)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("\"" + current.name() + "\" has blocks 1 to "
                        + current.blocks().size() + "; getStudyCycle lists them"));

        BlockPatchDTO patch = new BlockPatchDTO();
        if (mode != null && !mode.isBlank()) patch.setMode(mode(mode, "mode"));
        if (plannedMinutes != null) patch.setPlannedMinutes(plannedMinutes);
        if (concept != null && !concept.isBlank()) {
            if (patch.has("mode") && patch.getMode() == BlockMode.TIME) {
                throw new IllegalArgumentException("A TIME block has no concept: send mode GOAL with it, or leave "
                        + "the concept out");
            }
            patch.setMode(BlockMode.GOAL);
            if (concept.strip().equalsIgnoreCase(GoalSource.NEXT.name())) {
                patch.setGoalSource(GoalSource.NEXT);
            } else {
                patch.setGoalSource(GoalSource.PAGE);
                patch.setPageId(subject(user, concept).getId());
            }
        }
        if (requireQuiz != null) patch.setRequireQuiz(requireQuiz);
        // An empty note is how the model clears one; the service stores a blank note as none.
        if (note != null) patch.setNote(note);
        if (!(patch.has("mode") || patch.has("plannedMinutes") || patch.has("requireQuiz") || patch.has("note"))) {
            throw new IllegalArgumentException("Say what to change: mode, plannedMinutes, concept, requireQuiz or note");
        }

        StudyCycleDTO cycle = cycleService.patchBlock(user, current.id(), block.id(), patch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", "Changed block " + block.position() + " of \"" + cycle.name() + "\"");
        cycle.blocks().stream()
                .filter(b -> b.id().equals(block.id()))
                .findFirst()
                .ifPresent(b -> out.put("block", block(b)));
        return out;
    }

    /** Puts a cycle away: its subjects are free again and its history stays in the account. */
    public Map<String, Object> archive(User user, String cycleRef) {
        StudyCycleSummaryDTO cycle = activeCycle(user, cycleRef);
        cycleService.archive(user, cycle.id());
        return Map.of("success", "Archived \"" + cycle.name() + "\". Its subjects are free for another cycle "
                + "and its history stays in the account");
    }

    /** Deletes a cycle and everything under it. Pomodoros and logged sessions stay. */
    public Map<String, Object> delete(User user, String cycleRef) {
        StudyCycleSummaryDTO cycle = activeCycle(user, cycleRef);
        cycleService.delete(user, cycle.id());
        return Map.of("success", "Deleted \"" + cycle.name() + "\" with its blocks, laps and history. Its "
                + "subjects are free, and pomodoros and logged sessions stay in the account");
    }

    /**
     * A manual session on a subject, credited to the cycle that holds it. A page inside a subject
     * works too: the minutes go to the subject it lives in, as a pomodoro on it would.
     */
    public Map<String, Object> logSession(User user, String topic, Integer minutes, String note, String date) {
        NotebookPage page = subject(user, topic);
        if (minutes == null || minutes < 1 || minutes > 600) {
            throw new IllegalArgumentException("minutes is a whole number from 1 to 600");
        }
        LocalDate day = parseDate(date);
        SessionResponseDTO response = cycleService.logSession(user, new SessionRequestDTO(page.getId(), null, minutes,
                day == null ? UserDateResolver.today(user) : day, note));
        Map<String, Object> out = new LinkedHashMap<>();
        if (response.credited() == null) {
            out.put("success", "Logged " + minutes + " minutes of \"" + page.getTitle()
                    + "\". No active study cycle holds it or a page above it, so no block moved");
        } else {
            SessionResponseDTO.Credited c = response.credited();
            out.put("success", "Logged " + minutes + " minutes of \"" + page.getTitle() + "\" in \"" + c.cycleName()
                    + "\": block " + c.position() + " went from " + c.minutesBefore() + " to " + c.minutesAfter()
                    + " of " + c.plannedMinutes() + " minutes (" + c.state() + ")");
        }
        out.put("sessionId", response.id());
        return out;
    }

    /**
     * Deletes a session logged by hand. Its minutes come back off the lap they went into while
     * that lap is still running; a closed lap keeps its numbers. Correcting a session is this and
     * a new {@link #logSession}.
     */
    public Map<String, Object> deleteSession(User user, String sessionRef) {
        Matcher id = sessionRef == null ? null : UUID_PATTERN.matcher(sessionRef);
        if (id == null || !id.find()) {
            throw new IllegalArgumentException("Send the session's id; listStudySessions lists the recent ones");
        }
        UUID wanted = UUID.fromString(id.group());
        StudySession session = sessionRepository.findById(wanted)
                .filter(s -> s.getUser() != null && user.getId().equals(s.getUser().getId()))
                .orElseThrow(() -> new IllegalArgumentException("No session logged by this user has the id " + wanted
                        + "; listStudySessions lists the recent ones"));
        String what = session.getMinutes() + " minutes of \"" + labels(List.of(session.getTopicId()))
                .apply(session.getTopicId()) + "\" on " + session.getStudiedOn();
        cycleService.deleteSession(user, wanted);
        return Map.of("success", "Deleted the session of " + what + ". If its minutes were in a lap still "
                + "running, they came off it; a closed lap keeps its numbers");
    }

    // ------------------------------------------------------------- resolving

    /**
     * An active cycle by id or name, in any case. Refuses rather than guesses, and lists the cycles
     * there are. An id that is no active cycle of this user's gets the same refusal as an unknown
     * name, whoever it belongs to.
     */
    StudyCycleSummaryDTO activeCycle(User user, String ref) {
        List<StudyCycleSummaryDTO> cycles = cycleService.list(user);
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Say which cycle, by its name or its id. " + cyclesLine(cycles));
        }
        Matcher id = UUID_PATTERN.matcher(ref);
        if (id.find()) {
            UUID wanted = UUID.fromString(id.group());
            return cycles.stream().filter(c -> c.id().equals(wanted)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No active study cycle has the id " + wanted + ". "
                            + cyclesLine(cycles)));
        }
        String name = ref.strip();
        List<StudyCycleSummaryDTO> matches = cycles.stream().filter(c -> c.name().equalsIgnoreCase(name)).toList();
        if (matches.size() == 1) return matches.get(0);
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Several active cycles are named \"" + name + "\": "
                    + matches.stream().map(c -> "id " + c.id()).collect(Collectors.joining(", "))
                    + ". Ask which one, or use its id");
        }
        throw new IllegalArgumentException("No active study cycle is named \"" + name + "\". " + cyclesLine(cycles));
    }

    private static String cyclesLine(List<StudyCycleSummaryDTO> cycles) {
        return cycles.isEmpty() ? "The user has no active study cycle"
                : "The active cycles are " + cycles.stream().map(c -> "\"" + c.name() + "\"")
                        .collect(Collectors.joining(", "));
    }

    /** "Root › Title" for each page id, the title alone for a topic, "a deleted page" for one that is gone. */
    private Function<UUID, String> labels(Collection<UUID> pageIds) {
        if (pageIds.isEmpty()) return pageId -> "a deleted page";
        Map<UUID, NotebookPage> pages = pageRepository.findByIdIn(pageIds).stream()
                .collect(Collectors.toMap(NotebookPage::getId, p -> p));
        Set<UUID> missingRoots = new HashSet<>();
        pages.values().stream().filter(p -> !p.isTopic() && !pages.containsKey(p.rootId()))
                .forEach(p -> missingRoots.add(p.rootId()));
        Map<UUID, String> roots = new LinkedHashMap<>();
        pages.values().forEach(p -> roots.put(p.getId(), p.getTitle()));
        if (!missingRoots.isEmpty()) {
            pageRepository.findByIdIn(missingRoots).forEach(p -> roots.put(p.getId(), p.getTitle()));
        }
        return pageId -> {
            NotebookPage page = pages.get(pageId);
            if (page == null) return "a deleted page";
            return page.isTopic() ? page.getTitle() : roots.getOrDefault(page.rootId(), "?") + " › " + page.getTitle();
        };
    }

    /**
     * A subject by id or exact title, in any case: a topic or any page inside one. Refuses rather
     * than guesses. An ambiguous title lists each candidate with the topic it lives in, which is
     * what tells "Fundamentos" in one topic from "Fundamentos" in another.
     */
    NotebookPage subject(User user, String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Say which subject: a topic or a page inside one, by its exact title "
                    + "or its id");
        }
        Matcher id = UUID_PATTERN.matcher(ref);
        if (id.find()) {
            UUID wanted = UUID.fromString(id.group());
            return pageRepository.findById(wanted)
                    .filter(p -> p.getUser() != null && user.getId().equals(p.getUser().getId()))
                    .orElseThrow(() -> new IllegalArgumentException("No notebook page has the id " + wanted
                            + ". " + topicsLine(user)));
        }
        String title = ref.strip();
        List<NotebookPage> matches = pageRepository.findByUserIdAndTitleIgnoreCase(user.getId(), title);
        if (matches.size() == 1) return matches.get(0);
        if (matches.size() > 1) {
            Map<UUID, String> roots = pageRepository.findByIdIn(matches.stream().map(NotebookPage::rootId).distinct().toList())
                    .stream().collect(Collectors.toMap(NotebookPage::getId, NotebookPage::getTitle));
            throw new IllegalArgumentException("Several pages are titled \"" + title + "\": "
                    + matches.stream().map(p -> candidate(p, roots)).collect(Collectors.joining("; "))
                    + ". Ask which one, or use its id");
        }
        throw new IllegalArgumentException("No notebook page is titled \"" + title + "\". " + topicsLine(user));
    }

    private static String candidate(NotebookPage page, Map<UUID, String> roots) {
        String where = page.isTopic() ? "a topic" : "in \"" + roots.getOrDefault(page.rootId(), "?") + "\"";
        return "\"" + page.getTitle() + "\" (" + where + ", id " + page.getId() + ")";
    }

    private String topicsLine(User user) {
        List<NotebookPage> topics = pageRepository.findByUserIdAndKindOrderByCreatedAtAsc(user.getId(), NotebookPageKind.TOPIC);
        return topics.isEmpty() ? "The user has no topics yet"
                : "The topics are " + quoted(topics) + "; a page inside one works too (getStudyBoard lists a board's pages)";
    }

    /** "Root › Subject" for a subject inside a topic, the title alone for a topic. */
    private static String label(SubjectRefDTO subject) {
        if (subject == null) return null;
        return subject.rootTitle() == null ? subject.title() : subject.rootTitle() + " › " + subject.title();
    }

    /**
     * Seven days: the days sent on top of {@code base}, or on top of a week of rest days when there
     * is no base (a new cycle). Minutes snap to the 5-minute steps the week uses. On top of a base, a
     * day sent without minutes or without a start time keeps the one it had, and "none" takes the
     * start time away.
     */
    private static List<CycleDayDTO> week(List<DayInput> days, List<CycleDayDTO> base) {
        Map<DayOfWeek, CycleDayDTO> week = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            week.put(day, new CycleDayDTO(day, 0, null));
        }
        if (base != null) base.forEach(day -> week.put(day.weekday(), day));
        if (days != null) {
            for (DayInput input : days) {
                if (input == null) continue;
                DayOfWeek day = weekday(input.weekday());
                CycleDayDTO was = week.get(day);
                int minutes = input.minutes() == null ? was.minutes() : Math.round(input.minutes() / 5f) * 5;
                if (minutes < 0 || minutes > 720) {
                    throw new IllegalArgumentException("A day's minutes are 0 to 720");
                }
                String start = input.startTime() == null ? was.startTime()
                        : input.startTime().strip().equalsIgnoreCase("none") ? null : time(input.startTime());
                week.put(day, new CycleDayDTO(day, minutes, start));
            }
        }
        return new ArrayList<>(week.values());
    }

    private static DayOfWeek weekday(String value) {
        try {
            return DayOfWeek.valueOf(WeekDay.fromJson(value).name().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown weekday \"" + value + "\": use Monday..Sunday or the Portuguese names");
        }
    }

    private static String time(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalTime.parse(value.strip(), LENIENT_TIME).format(HH_MM);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid startTime \"" + value + "\": send HH:mm, e.g. 19:00");
        }
    }

    private static BlockMode mode(String value, String field) {
        String wanted = value.strip().toUpperCase(Locale.ROOT);
        for (BlockMode mode : BlockMode.values()) {
            if (mode.name().equals(wanted)) return mode;
        }
        throw new IllegalArgumentException(field + " is TIME (minutes on the topic) or GOAL (a concept to understand)");
    }

    private static int maxBlock(int minutes) {
        if (minutes < 15 || minutes > 240) {
            throw new IllegalArgumentException("maxBlockMinutes is 15 to 240 (60, 90 or 120 are the usual ones)");
        }
        return minutes;
    }

    private static int scale(Integer value, String field) {
        return scale(value, 3, field);
    }

    private static int scale(Integer value, int fallback, String field) {
        int v = value == null ? fallback : value;
        if (v < 1 || v > 5) throw new IllegalArgumentException(field + " is 1 to 5");
        return v;
    }

    /** What a create or an edit answers: the blocks the planner laid out, one line each. */
    private static Map<String, Object> planned(String verb, StudyCycleDTO cycle) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", verb + " \"" + cycle.name() + "\" with " + cycle.blocks().size()
                + " blocks; one lap is " + cycle.lap().plannedMinutes() + " minutes");
        out.put("blocks", cycle.blocks().stream()
                .map(b -> b.position() + ". " + label(b.topic()) + " " + b.plannedMinutes() + " min "
                        + (b.mode() == BlockMode.GOAL ? "(understand)" : "(time)"))
                .toList());
        return out;
    }

    private static Map<String, Object> day(CycleDayDTO day) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("weekday", day.weekday());
        row.put("minutes", day.minutes());
        if (day.startTime() != null) row.put("startTime", day.startTime());
        return row;
    }

    private static Map<String, Object> subjectRow(CycleTopicViewDTO topic) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("subject", label(topic.topic()));
        row.put("id", topic.topicId());
        row.put("importance", topic.importance());
        row.put("mastery", topic.mastery());
        if (topic.minutesOverride() != null) row.put("minutesPerLapSetByHand", topic.minutesOverride());
        return row;
    }

    private static Map<String, Object> block(StudyBlockDTO block) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("block", block.position());
        row.put("subject", label(block.topic()));
        row.put("mode", block.mode());
        if (block.mode() == BlockMode.GOAL) {
            row.put("concept", block.goalSource() == GoalSource.PAGE && block.page() != null ? block.page().title()
                    : block.progress().target() != null ? "NEXT (now " + block.progress().target().title() + ")" : "NEXT");
            if (block.requireQuiz()) row.put("requireQuiz", true);
        }
        row.put("minutes", block.progress().minutes() + " of " + block.plannedMinutes());
        row.put("state", block.progress().state());
        if (block.note() != null) row.put("note", block.note());
        return row;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date \"" + value + "\": send YYYY-MM-DD, or leave it out for today");
        }
    }

    private static Map<String, Object> entry(TodayEntryDTO entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("block", entry.position());
        row.put("topic", label(entry.topic()));
        row.put("mode", entry.mode());
        if (entry.target() != null) {
            row.put(entry.targetPreview() ? "conceptIfStillOpen" : "concept", entry.target().title());
            row.put("conceptProgress", entry.target().progress().done() + " of " + entry.target().progress().total());
        }
        if (entry.note() != null) row.put("note", entry.note());
        row.put("minutes", entry.credited() + " of " + entry.plannedMinutes());
        row.put("minutesToday", entry.minutesToday());
        if (entry.start() != null) row.put("time", entry.start() + "-" + entry.end());
        if (entry.doneToday()) row.put("closedToday", entry.state());
        if (entry.nextLap()) row.put("nextLap", true);
        if (entry.cardsDue() > 0) row.put("cardsDue", entry.cardsDue());
        return row;
    }

    private static String quoted(List<NotebookPage> topics) {
        return topics.stream().map(t -> "\"" + t.getTitle() + "\"").collect(Collectors.joining(", "));
    }
}

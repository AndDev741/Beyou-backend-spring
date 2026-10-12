package beyou.beyouapp.backend.domain.aiAgent.notebook;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookPageKind;
import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.cycle.BlockMode;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyCycleService;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleConfigRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleDayDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleTopicDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.SessionRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.SessionResponseDTO;
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
 * on weekdays", "log 45 minutes of Portuguese".
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

    /** A day of the week for {@code createStudyCycle}. */
    public record DayInput(String weekday, Integer minutes, String startTime) {
    }

    /** A subject for {@code createStudyCycle}: a topic or a page inside one, by title or id. */
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
        int maxBlock = maxBlockMinutes == null ? 90 : maxBlockMinutes;
        if (maxBlock < 15 || maxBlock > 240) {
            throw new IllegalArgumentException("maxBlockMinutes is 15 to 240 (60, 90 or 120 are the usual ones)");
        }
        CycleConfigRequestDTO request = new CycleConfigRequestDTO(name, mode(defaultMode), maxBlock, week(days),
                topicDtos);
        StudyCycleDTO cycle = cycleService.create(user, request);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", "Created the study cycle \"" + cycle.name() + "\" with " + cycle.blocks().size()
                + " blocks; one lap is " + cycle.lap().plannedMinutes() + " minutes");
        out.put("blocks", cycle.blocks().stream()
                .map(b -> b.position() + ". " + label(b.topic()) + " " + b.plannedMinutes() + " min "
                        + (b.mode() == BlockMode.GOAL ? "(understand)" : "(time)"))
                .toList());
        return out;
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
        return out;
    }

    // ------------------------------------------------------------- resolving

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

    /** Seven days; a day left out rests. Minutes snap to the 5-minute steps the week uses. */
    private static List<CycleDayDTO> week(List<DayInput> days) {
        Map<DayOfWeek, CycleDayDTO> week = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            week.put(day, new CycleDayDTO(day, 0, null));
        }
        if (days != null) {
            for (DayInput input : days) {
                if (input == null) continue;
                DayOfWeek day = weekday(input.weekday());
                int minutes = input.minutes() == null ? 0 : Math.round(input.minutes() / 5f) * 5;
                if (minutes < 0 || minutes > 720) {
                    throw new IllegalArgumentException("A day's minutes are 0 to 720");
                }
                week.put(day, new CycleDayDTO(day, minutes, time(input.startTime())));
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

    private static BlockMode mode(String value) {
        if (value == null || value.isBlank()) return BlockMode.GOAL;
        String wanted = value.strip().toUpperCase(Locale.ROOT);
        for (BlockMode mode : BlockMode.values()) {
            if (mode.name().equals(wanted)) return mode;
        }
        throw new IllegalArgumentException("defaultMode is TIME (minutes on the topic) or GOAL (a concept to understand)");
    }

    private static int scale(Integer value, String field) {
        int v = value == null ? 3 : value;
        if (v < 1 || v > 5) throw new IllegalArgumentException(field + " is 1 to 5");
        return v;
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

package beyou.beyouapp.backend.domain.secondbrain;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.briefing.DailyBriefingFactsBuilder;
import beyou.beyouapp.backend.domain.briefing.DailyBriefingNarrator;
import beyou.beyouapp.backend.domain.briefing.WeekSignals;
import beyou.beyouapp.backend.domain.briefing.dto.GoalAhead;
import beyou.beyouapp.backend.domain.briefing.dto.GoalPace;
import beyou.beyouapp.backend.domain.calendar.CalendarTask;
import beyou.beyouapp.backend.domain.calendar.CalendarTaskRepository;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.mood.MoodEntryRepository;
import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookProgressService;
import beyou.beyouapp.backend.domain.notebook.ProgressGraph;
import beyou.beyouapp.backend.domain.notebook.dto.ProgressDTO;
import beyou.beyouapp.backend.domain.quicknote.QuickNote;
import beyou.beyouapp.backend.domain.quicknote.QuickNoteRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheck;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotItemType;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainAreaDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainFactDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainQuestionDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainRoutineDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainRoutineItemDTO;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * What Second Brain reads from the rest of Beyou, and the only place it reads it.
 *
 * <p>Two outputs from the same reads: the blocks the page draws ({@link #blocks}) and the text
 * the model is given ({@link #promptData}). Both take the set of enabled sources, and <b>a
 * source that is off is not queried at all</b>, so it cannot reach the page or a prompt by any
 * path through here.
 *
 * <p>Like the briefing's facts, every number is computed here and handed to the model finished.
 * The week's patterns and the goals come from {@link DailyBriefingFactsBuilder}, so this page
 * and the morning dialog cannot disagree about the same week.
 *
 * <p><b>The mood journal is never read.</b> Mood comes through
 * {@link MoodEntryRepository#findLevels}, which does not select the note. The notebook
 * contributes topic titles and progress, never what a page says.
 */
@Component
@RequiredArgsConstructor
public class SecondBrainSignals {

    /** The areas block looks at the same two weeks the routine block does. */
    static final int AREA_DAYS = DailyBriefingFactsBuilder.SIGNAL_DAYS;
    /** How far ahead an open calendar task is worth telling the model about. */
    static final int CALENDAR_AHEAD_DAYS = 21;
    /** Caps on what one prompt carries from each list source. */
    static final int MAX_CALENDAR_TASKS = 30;
    static final int MAX_TOPICS = 20;
    static final int MAX_IDEAS = 15;

    private final DailyBriefingFactsBuilder briefingFacts;
    private final RoutineSnapshotRepository snapshotRepository;
    private final HabitRepository habitRepository;
    private final TaskRepository taskRepository;
    private final MoodEntryRepository moodEntryRepository;
    private final CalendarTaskRepository calendarTaskRepository;
    private final NotebookProgressService notebookProgressService;
    private final QuickNoteRepository quickNoteRepository;

    /**
     * What the page draws from the person's data.
     *
     * @param goalsAhead empty when GOALS is off
     * @param routine    null when ROUTINES is off
     * @param areas      empty when ROUTINES is off
     */
    public record Blocks(List<GoalAhead> goalsAhead, SecondBrainRoutineDTO routine, List<SecondBrainAreaDTO> areas) {}

    /** The page's blocks. Mood is not read: nothing on the page shows it. */
    @Transactional(readOnly = true)
    public Blocks blocks(User user, LocalDate today, Set<SecondBrainSource> enabled) {
        Read read = read(user, today, enabled, false);
        SecondBrainRoutineDTO routine = enabled.contains(SecondBrainSource.ROUTINES) ? toRoutine(read.week()) : null;
        return new Blocks(read.goals(), routine, read.areas());
    }

    /**
     * Everything the model may know about the person, as plain labelled lines: one section per
     * enabled source, then the profile. An enabled source with nothing in it says so, which
     * keeps the model from inventing what it was not given. A disabled one is absent.
     *
     * <p>No date is written in ISO form. Days are given as distances from today, so the model
     * has nothing to copy a date from.
     */
    @Transactional(readOnly = true)
    public String promptData(User user, LocalDate today, Set<SecondBrainSource> enabled, List<SecondBrainFactDTO> facts) {
        Read read = read(user, today, enabled, true);
        StringBuilder sb = new StringBuilder();
        if (enabled.contains(SecondBrainSource.ROUTINES)) {
            appendRoutines(sb, read.week(), read.areas());
        }
        if (enabled.contains(SecondBrainSource.GOALS)) {
            appendGoals(sb, read.goals());
        }
        if (enabled.contains(SecondBrainSource.MOOD)) {
            appendMood(sb, read.week());
        }
        if (enabled.contains(SecondBrainSource.CALENDAR)) {
            appendCalendar(sb, user.getId(), today);
        }
        if (enabled.contains(SecondBrainSource.NOTEBOOK)) {
            appendNotebook(sb, user.getId());
        }
        if (enabled.contains(SecondBrainSource.IDEAS)) {
            appendIdeas(sb, user.getId());
        }
        appendFacts(sb, facts);
        return sb.toString();
    }

    // ---- the shared reads ----

    private record Read(List<GoalAhead> goals, WeekSignals week, List<SecondBrainAreaDTO> areas) {}

    private Read read(User user, LocalDate today, Set<SecondBrainSource> enabled, boolean withMood) {
        UUID userId = user.getId();
        LocalDate yesterday = today.minusDays(1);
        LocalDate from = yesterday.minusDays(AREA_DAYS - 1L);

        boolean routines = enabled.contains(SecondBrainSource.ROUTINES);
        List<RoutineSnapshot> fortnight = routines
                ? snapshotRepository.findAllByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                        userId, from, yesterday)
                : List.of();

        Map<LocalDate, Integer> moods = new HashMap<>();
        if (withMood && enabled.contains(SecondBrainSource.MOOD)) {
            for (Object[] row : moodEntryRepository.findLevels(userId, from, yesterday)) {
                moods.put((LocalDate) row[0], (Integer) row[1]);
            }
        }

        return new Read(
                enabled.contains(SecondBrainSource.GOALS) ? briefingFacts.goalsAhead(user, today) : List.of(),
                DailyBriefingFactsBuilder.buildSignals(fortnight, moods, yesterday),
                routines ? areas(userId, fortnight) : List.of());
    }

    private static SecondBrainRoutineDTO toRoutine(WeekSignals week) {
        return new SecondBrainRoutineDTO(
                week.thisWeekPercent(),
                week.previousWeekPercent(),
                week.slipping().stream().map(SecondBrainSignals::toItem).toList(),
                week.steady().stream().map(SecondBrainSignals::toItem).toList());
    }

    private static SecondBrainRoutineItemDTO toItem(WeekSignals.ItemPattern pattern) {
        return new SecondBrainRoutineItemDTO(pattern.name(), pattern.asked(), pattern.checked(), pattern.skipped());
    }

    /**
     * The two weeks by category: which life areas the person keeps up and which they let go.
     *
     * <p>A snapshot check remembers the habit or task it was made from
     * ({@code originalItemId}), and that item carries its categories. Each check that came up
     * and was not skipped counts once for every category of its item, so an item in two
     * categories counts in both. A check whose habit or task has since been deleted has no
     * categories left to count under and is left out. Skips are out of both sides, as in
     * {@link WeekSignals}: a skip is an answer, not a miss.
     *
     * <p>Only categories with something asked appear, best percentage first.
     */
    private List<SecondBrainAreaDTO> areas(UUID userId, List<RoutineSnapshot> fortnight) {
        if (fortnight.isEmpty()) {
            return List.of();
        }
        // Both reads fetch the categories in the same query (see the repositories).
        Map<UUID, List<Category>> habitCategories = new HashMap<>();
        habitRepository.findAllByUserId(userId)
                .forEach(habit -> habitCategories.put(habit.getId(), habit.getCategories()));
        Map<UUID, List<Category>> taskCategories = new HashMap<>();
        taskRepository.findAllByUserId(userId).orElseGet(List::of)
                .forEach(task -> taskCategories.put(task.getId(), task.getCategories()));

        Map<UUID, Category> categories = new LinkedHashMap<>();
        Map<UUID, int[]> counts = new HashMap<>();
        for (RoutineSnapshot snapshot : fortnight) {
            for (SnapshotCheck check : snapshot.getChecks()) {
                if (check.isSkipped() || check.getOriginalItemId() == null) {
                    continue;
                }
                List<Category> of = (check.getItemType() == SnapshotItemType.HABIT
                        ? habitCategories : taskCategories).get(check.getOriginalItemId());
                if (of == null) {
                    continue;
                }
                // Distinct by id: a join can hand the same category back twice.
                Map<UUID, Category> distinct = new LinkedHashMap<>();
                of.forEach(category -> distinct.putIfAbsent(category.getId(), category));
                for (Category category : distinct.values()) {
                    categories.putIfAbsent(category.getId(), category);
                    int[] askedAndChecked = counts.computeIfAbsent(category.getId(), id -> new int[2]);
                    askedAndChecked[0]++;
                    if (check.isChecked()) {
                        askedAndChecked[1]++;
                    }
                }
            }
        }

        List<SecondBrainAreaDTO> areas = new ArrayList<>();
        counts.forEach((categoryId, askedAndChecked) -> {
            Category category = categories.get(categoryId);
            int asked = askedAndChecked[0];
            int checked = askedAndChecked[1];
            areas.add(new SecondBrainAreaDTO(categoryId, category.getName(), category.getIconId(),
                    asked, checked, (int) Math.round(checked * 100d / asked)));
        });
        areas.sort(Comparator.comparing(SecondBrainAreaDTO::percent).reversed()
                .thenComparing(Comparator.comparingInt(SecondBrainAreaDTO::asked).reversed())
                .thenComparing(SecondBrainAreaDTO::name));
        return List.copyOf(areas);
    }

    // ---- the prompt's sections ----

    private static void appendRoutines(StringBuilder sb, WeekSignals week, List<SecondBrainAreaDTO> areas) {
        sb.append("ROUTINES\n");
        if (week.thisWeekPercent() == null && week.previousWeekPercent() == null && areas.isEmpty()) {
            sb.append("- No routine history in the last two weeks.\n");
            return;
        }
        if (week.thisWeekPercent() != null) {
            sb.append("- Over the seven days ending yesterday, ").append(week.thisWeekPercent())
              .append("% of the routine items asked were checked");
            if (week.previousWeekPercent() != null) {
                sb.append(" (the seven days before: ").append(week.previousWeekPercent()).append("%)");
            }
            sb.append(". A skip is a choice the person made and is left out of both percentages.\n");
        } else {
            sb.append("- Nothing was asked this week; the week before, ")
              .append(week.previousWeekPercent()).append("% of the items asked were checked.\n");
        }
        for (WeekSignals.ItemPattern item : week.slipping()) {
            sb.append("- Slipping: \"").append(SecondBrainData.inline(item.name())).append("\" ")
              .append(DailyBriefingNarrator.slippingDetail(item));
        }
        for (WeekSignals.ItemPattern item : week.steady()) {
            sb.append("- Steady: \"").append(SecondBrainData.inline(item.name())).append("\" was done all ")
              .append(item.asked()).append(" times it came up this week.\n");
        }
        for (SecondBrainAreaDTO area : areas) {
            sb.append("- Life area \"").append(SecondBrainData.inline(area.name())).append("\": ")
              .append(area.percent()).append("% of its routine items checked over the last two weeks (")
              .append(area.checked()).append(" of ").append(area.asked()).append(").\n");
        }
    }

    private static void appendGoals(StringBuilder sb, List<GoalAhead> goals) {
        sb.append("GOALS\n");
        if (goals.isEmpty()) {
            sb.append("- No open goal with an end date.\n");
            return;
        }
        for (GoalAhead goal : goals) {
            String unit = SecondBrainData.inline(goal.unit());
            sb.append("- Goal \"").append(SecondBrainData.inline(goal.name())).append("\": ")
              .append(goal.percentComplete()).append("% done (")
              .append(number(goal.currentValue())).append(" of ").append(number(goal.targetValue()))
              .append(' ').append(unit).append("), ");
            switch (goal.pace()) {
                case REACHED -> sb.append("target reached but not marked as done yet.");
                case OVERDUE -> sb.append("ended ").append(Math.abs(goal.daysRemaining()))
                        .append(" days ago without reaching the target.");
                case BEHIND, ON_TRACK -> {
                    sb.append(goal.daysRemaining() == 0 ? "ends today" : "ends in " + goal.daysRemaining() + " days");
                    sb.append(goal.pace() == GoalPace.BEHIND ? ", behind pace" : ", on pace");
                    if (goal.requiredPerDay() != null) {
                        sb.append(". Reaching it takes ").append(number(goal.requiredPerDay()))
                          .append(' ').append(unit).append(" a day from today on");
                    }
                    sb.append('.');
                }
            }
            sb.append('\n');
        }
    }

    private static void appendMood(StringBuilder sb, WeekSignals week) {
        sb.append("MOOD (levels only, 1 awful to 5 great)\n");
        if (week.moodAverage() == null && week.previousMoodAverage() == null) {
            sb.append("- No mood logged in the last two weeks.\n");
            return;
        }
        if (week.moodAverage() != null) {
            sb.append("- This week averaged ").append(number(week.moodAverage()))
              .append(" over ").append(week.moodDaysLogged()).append(" days logged.\n");
        }
        if (week.previousMoodAverage() != null) {
            sb.append("- The week before averaged ").append(number(week.previousMoodAverage())).append(".\n");
        }
    }

    private void appendCalendar(StringBuilder sb, UUID userId, LocalDate today) {
        sb.append("CALENDAR (open tasks: the next three weeks, and the overdue ones)\n");
        List<CalendarTask> tasks = new ArrayList<>(calendarTaskRepository
                .findByUserIdAndCompletedAtIsNullAndTaskDateLessThanEqualOrderByTaskDateDescCreatedAtDesc(
                        userId, today.plusDays(CALENDAR_AHEAD_DAYS), Limit.of(MAX_CALENDAR_TASKS)));
        if (tasks.isEmpty()) {
            sb.append("- Nothing open.\n");
            return;
        }
        tasks.sort(Comparator.comparing(CalendarTask::getTaskDate));
        for (CalendarTask task : tasks) {
            sb.append("- \"").append(SecondBrainData.inline(task.getTitle())).append("\": ")
              .append(when(task.getTaskDate(), today)).append(".\n");
        }
    }

    /** A day as a distance from today, with the weekday where it helps. Never an ISO date. */
    private static String when(LocalDate day, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, day);
        if (days < 0) {
            return "overdue by " + (-days) + (days == -1 ? " day" : " days");
        }
        if (days == 0) {
            return "today";
        }
        if (days == 1) {
            return "tomorrow";
        }
        return "in " + days + " days, on a "
                + day.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private void appendNotebook(StringBuilder sb, UUID userId) {
        sb.append("NOTEBOOK (study topics, titles and progress only)\n");
        ProgressGraph graph = notebookProgressService.graphFor(userId);
        List<NotebookPage> topics = graph.pages().stream()
                .filter(NotebookPage::isTopic)
                .sorted(Comparator.comparing(NotebookPage::getUpdatedAt).reversed())
                .limit(MAX_TOPICS)
                .toList();
        if (topics.isEmpty()) {
            sb.append("- No study topic.\n");
            return;
        }
        for (NotebookPage topic : topics) {
            sb.append("- \"").append(SecondBrainData.inline(topic.getTitle())).append("\": ");
            // A topic with no board counts as its own single leaf, which is not a subtopic.
            if (!graph.hasBoard(topic.getId())) {
                sb.append("no subtopics yet.\n");
            } else {
                ProgressDTO progress = graph.progressOf(topic.getId());
                sb.append(progress.done()).append(" of ").append(progress.total()).append(" subtopics done.\n");
            }
        }
    }

    private void appendIdeas(StringBuilder sb, UUID userId) {
        sb.append("IDEAS (the most recent notes on the ideas board, newest first)\n");
        List<QuickNote> notes = quickNoteRepository
                .findByUserIdAndArchivedAtIsNullOrderByCreatedAtDescIdAsc(userId, Limit.of(MAX_IDEAS));
        if (notes.isEmpty()) {
            sb.append("- The board is empty.\n");
            return;
        }
        for (QuickNote note : notes) {
            sb.append("- \"").append(SecondBrainData.inline(note.getText())).append("\"\n");
        }
    }

    private static void appendFacts(StringBuilder sb, List<SecondBrainFactDTO> facts) {
        sb.append("PROFILE (what the person told Beyou about themselves)\n");
        if (facts.isEmpty()) {
            sb.append("- Nothing yet.\n");
            return;
        }
        for (SecondBrainFactDTO fact : facts) {
            sb.append("- ").append(factLabel(fact.kind())).append(": ")
              .append(SecondBrainData.inline(fact.text())).append('\n');
        }
    }

    /** How much of one answer the overview is shown. An answer can run to 2000 characters. */
    static final int ANSWER_EXCERPT = 300;

    /**
     * The person's latest answers as a section of the overview's data. They say what the
     * numbers cannot: why a week went the way it did, in the person's own words.
     */
    public static String recentAnswers(List<SecondBrainQuestionDTO> answered) {
        StringBuilder sb = new StringBuilder("RECENT ANSWERS (what the person told Beyou, newest first)\n");
        if (answered.isEmpty()) {
            return sb.append("- None yet.\n").toString();
        }
        for (SecondBrainQuestionDTO question : answered) {
            sb.append("- Asked \"").append(SecondBrainData.inline(question.text())).append("\", answered \"")
              .append(SecondBrainStore.truncate(SecondBrainData.inline(question.answer()), ANSWER_EXCERPT))
              .append("\"\n");
        }
        return sb.toString();
    }

    /**
     * The profile as the assistant chat reads it: one line per fact, oldest first. Empty for
     * an empty profile, so the caller decides how to say "nothing yet".
     *
     * <p>Every line goes through {@link SecondBrainData#inline}: a fact is something a person
     * typed, and it must stay one line of a list wherever it is quoted.
     */
    public static String profileLines(List<SecondBrainFactDTO> facts) {
        StringBuilder sb = new StringBuilder();
        for (SecondBrainFactDTO fact : facts) {
            String text = SecondBrainData.inline(fact.text());
            if (text.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(factLabel(fact.kind())).append(": ").append(text);
        }
        return sb.toString();
    }

    static String factLabel(SecondBrainFactKind kind) {
        return switch (kind) {
            case OBJECTIVE -> "Objective";
            case LIKE -> "Likes";
            case ABOUT -> "About them";
        };
    }

    /** Whole numbers without a trailing .0, which is how a person writes a count. */
    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}

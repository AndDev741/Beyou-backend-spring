package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
import beyou.beyouapp.backend.domain.briefing.DailyBriefingService;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.checkday.CheckHistoryService;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.common.CheckProgress;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.feedback.FeedbackService;
import beyou.beyouapp.backend.domain.focus.FocusCycleRepository;
import beyou.beyouapp.backend.domain.focus.FocusMicroTaskRepository;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.ai.draft.RoadmapDraftService;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardEdgeRepository;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardNodeRepository;
import beyou.beyouapp.backend.domain.notebook.card.NotebookCardRepository;
import beyou.beyouapp.backend.domain.notebook.card.NotebookCardReviewRepository;
import beyou.beyouapp.backend.domain.notebook.source.NotebookSourceRepository;
import beyou.beyouapp.backend.domain.notebook.study.NotebookChatMessageRepository;
import beyou.beyouapp.backend.domain.notebook.study.NotebookStudyOutputRepository;
import beyou.beyouapp.backend.domain.mood.MoodService;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.notification.engagement.NotificationSendRepository;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferences;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferencesRepository;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.federation.FederatedIdentityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserExportService {

    private final AuthenticatedUser authenticatedUser;
    private final CategoryRepository categoryRepository;
    private final HabitRepository habitRepository;
    private final GoalRepository goalRepository;
    private final TaskRepository taskRepository;
    private final FeedbackService feedbackService;
    private final EntityCheckDayRepository entityCheckDayRepository;
    private final DiaryRoutineRepository diaryRoutineRepository;
    private final ChatService chatService;
    private final PhotoStorageService photoStorageService;
    private final NotificationPreferencesRepository notificationPreferencesRepository;
    private final MoodService moodService;
    private final NotebookPageRepository notebookPageRepository;
    private final NotebookCardRepository notebookCardRepository;
    private final NotebookSourceRepository notebookSourceRepository;
    private final NotebookChatMessageRepository notebookChatMessageRepository;
    private final RoadmapDraftService roadmapDraftService;
    private final NotebookBoardNodeRepository notebookBoardNodeRepository;
    private final NotebookBoardEdgeRepository notebookBoardEdgeRepository;
    private final NotebookCardReviewRepository notebookCardReviewRepository;
    private final NotebookStudyOutputRepository notebookStudyOutputRepository;
    private final FocusCycleRepository focusCycleRepository;
    private final FocusMicroTaskRepository focusMicroTaskRepository;
    private final FederatedIdentityRepository federatedIdentityRepository;
    private final DailyBriefingService dailyBriefingService;
    private final NotificationSendRepository notificationSendRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> exportUserData() {
        User user = authenticatedUser.getAuthenticatedUser();
        UUID userId = user.getId();

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("exportedAt", Instant.now().toString());

        // Profile
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", user.getName());
        profile.put("email", user.getEmail());
        profile.put("photo", photo(user));
        profile.put("createdAt", user.getCreatedAt());
        profile.put("isGoogleAccount", user.isGoogleAccount());
        profile.put("phrase", user.getPerfilPhrase());
        profile.put("phraseAuthor", user.getPerfilPhraseAuthor());
        profile.put("timezone", user.getTimezone());
        profile.put("language", user.getLanguageInUse());
        profile.put("theme", user.getThemeInUse());
        profile.put("widgetsInUse", user.getWidgetsIdInUse() == null
                ? List.of() : List.copyOf(user.getWidgetsIdInUse()));
        // The note the assistant keeps about this person ACROSS conversations. It is
        // written by a model, about a user, and stored on their row — an inference held
        // about someone is their data whether or not they typed it.
        profile.put("assistantNotesAboutYou", user.getUserContext());
        profile.put("progress", xp(user.getXpProgress()));
        profile.put("streak", streak(user.getCheckProgress()));
        // A setting the account owns, and one that lives outside the users table, so it
        // would be silently missing from a download that claims to be the whole account.
        // The default is what an account with no row gets; see V24 on why absence means
        // opted in. The unsubscribe token deliberately does NOT travel: it is a
        // capability that works without a session, and this file gets mailed around.
        profile.put("engagementEmails", notificationPreferencesRepository.findById(userId)
                .map(NotificationPreferences::isEngagementEmail)
                .orElse(true));
        profile.put("linkedSignIns", linkedSignIns(userId));
        export.put("profile", profile);

        // Categories
        var categories = categoryRepository.findAllByUserId(userId).orElse(new java.util.ArrayList<>());
        export.put("categories", categories.stream().map(c -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", c.getId());
            map.put("name", c.getName());
            map.put("iconId", c.getIconId());
            map.put("description", c.getDescription());
            map.put("progress", xp(c.getXpProgress()));
            return map;
        }).toList());

        // Habits
        var habits = habitRepository.findAllByUserId(userId);
        export.put("habits", habits.stream().map(h -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", h.getId());
            map.put("name", h.getName());
            map.put("description", h.getDescription());
            map.put("importance", h.getImportance());
            map.put("difficulty", h.getDificulty());
            map.put("motivationalPhrase", h.getMotivationalPhrase());
            map.put("progress", xp(h.getXpProgress()));
            map.put("streak", streak(h.getCheckProgress()));
            return map;
        }).toList());

        // Goals
        var goals = goalRepository.findAllByUserId(userId).orElse(List.of());
        export.put("goals", goals.stream().map(g -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", g.getId());
            map.put("name", g.getName());
            map.put("description", g.getDescription());
            map.put("targetValue", g.getTargetValue());
            map.put("currentValue", g.getCurrentValue());
            map.put("status", g.getStatus());
            map.put("startDate", g.getStartDate());
            map.put("endDate", g.getEndDate());
            // Null for an active goal. An export that dropped this would hand back archived
            // goals looking like live ones.
            map.put("archivedAt", g.getArchivedAt());
            // The goal this one sits under, or null at the top level. Read off the mirror
            // column, so it costs no query per goal. Without it the tree comes out flat and
            // a reader cannot tell a sub-goal from a goal of its own.
            map.put("parentId", g.getParentId());
            return map;
        }).toList());

        // Tasks
        var tasks = taskRepository.findAllByUserId(userId).orElse(List.of());
        export.put("tasks", tasks.stream().map(t -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", t.getId());
            map.put("name", t.getName());
            map.put("description", t.getDescription());
            map.put("importance", t.getImportance());
            map.put("difficulty", t.getDificulty());
            // Tasks carry CheckProgress exactly as habits do, and it was leaving in
            // silence — the kind of omission that made the export dishonest in the
            // first place.
            map.put("streak", streak(t.getCheckProgress()));
            return map;
        }).toList());

        // Routines, with the structure that makes them mean anything (R8)
        export.put("routines", routines(userId));

        // Feedback (R21) — submissions, the replies they got back, and
        // references to any attached images. Assembled by the feedback domain
        // itself; the shape of a submission is not this class's business.
        export.put("feedback", feedbackService.exportForUser(userId));

        // Assistant conversations, the transcript and the notes the model wrote. This
        // is the part of the account that left the server for a third-party provider,
        // which makes it the part someone asking for their data most wants to see.
        export.put("agentChats", chatService.exportForUser(userId));

        // Check-in history (R10)
        export.put("checkHistory", checkHistory(user));

        // Mood entries and the journal written alongside them. The most personal thing the
        // product stores, so an export that left it out would not be an export — and the
        // note is included in full, because a summary of someone's diary is not their diary.
        export.put("moodEntries", moodService.findAllForExport(userId).stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("date", m.getEntryDate());
            map.put("mood", m.getMood());
            map.put("note", m.getNote());
            map.put("updatedAt", m.getUpdatedAt());
            return map;
        }).toList());

        // The study notebook: one query per table, never per page, so the export stays flat in
        // query count however big the notebook grows (UserExportQueryCountTest).
        export.put("notebook", notebook(userId));

        // Focus mode: every pomodoro and break that ran to the end, and the micro-tasks the
        // person broke their items into. The names are typed by them, so they travel in full.
        export.put("focus", focus(userId));

        // The prose of the morning dialog, for the days retention still holds. Assembled by
        // the briefing domain, which is the one that knows how the stored lines read back.
        export.put("dailyBriefings", dailyBriefingService.exportForUser(userId));

        // Which nudge mails went out and when. A record of what Beyou sent about the person
        // is theirs to see, and it is one tiny row per mail.
        export.put("engagementEmailsSent", notificationSendRepository.findByUserIdOrderBySentOnAsc(userId)
                .stream().map(send -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("kind", send.getKind());
                    map.put("sentOn", send.getSentOn());
                    return map;
                }).toList());

        // Say out loud what a reader will not find here, so the file can be trusted
        // as a whole rather than spot-checked. Deletion takes these too.
        Map<String, Object> omitted = new LinkedHashMap<>();
        omitted.put("routineSnapshots", "The per-day frozen copy of each routine, one row per "
                + "routine per day, each carrying a full copy of that day's structure. The "
                + "outcomes they record are in checkHistory, in bounded form; the copies "
                + "themselves would grow this file without limit.");
        omitted.put("notebookSourceText", "The text read out of the PDFs, links and pasted text "
                + "you added as notebook sources. It is a copy of documents you already have, and a "
                + "book's worth of it per source would bury the rest of this file; the sources "
                + "themselves are listed under notebook.sources.");
        omitted.put("xpHistory", "The day-by-day XP ledger behind the progress chart. The "
                + "totals it adds up to are in every progress field above, and the app's XP "
                + "chart reads the daily breakdown a year at a time. A row per thing that earns "
                + "XP per day would otherwise outgrow the rest of this file.");
        omitted.put("dailyBriefingFacts", "The numbers in the morning dialog. They were never "
                + "stored: each open recomputes them from your habits, goals and check-ins, "
                + "which are all in this file. The generated lines that sat beside them are "
                + "under dailyBriefings.");
        omitted.put("credentials", "Password hash, refresh tokens, and any pending "
                + "verification, reset or account-deletion codes. Nothing here is useful to "
                + "you and all of it is dangerous in a file.");
        export.put("notIncluded", omitted);

        return export;
    }

    /**
     * The profile photo, as bytes rather than as a link.
     *
     * <p>This field used to be {@code user.getPerfilPhoto()} and nothing else, which
     * made it null for every account that uploaded a photo instead of signing in with
     * Google: the upload writes a JPEG to disk and never touches that column. So the
     * app showed a face on the profile screen while the export beside the delete button
     * reported no photo at all. The one binary asset an account owns was the one thing
     * missing from the file, and it was missing silently — not even listed under
     * {@code notIncluded}, which is what that block exists to prevent.
     *
     * <p>The bytes are inlined base64 rather than a URL because of what this file is
     * for. Someone downloads it, keeps it, and often deletes the account right after; a
     * link is worthless in both directions. A signed photo URL expires in twelve hours
     * by default ({@code app.photo-url-ttl-minutes}), and after the account is gone
     * there is nothing on the other end of it regardless. Inlining is affordable here
     * because uploads are re-encoded to at most 512x512 JPEG on the way in
     * ({@code PhotoStorageService.MAX_DIMENSION}), so this adds tens of kilobytes to a
     * payload that already carries every routine and conversation in the account.
     *
     * <p>Google's copy is the exception and stays a URL, because those bytes were never
     * ours: the account has a CDN link, not a file, and the export says which it is
     * instead of leaving a reader to guess from the shape.
     *
     * @return null when the account genuinely has no photo, so the absence reads as an
     *         answer rather than as a field that failed to populate
     */
    private Map<String, Object> photo(User user) {
        Path path = photoStorageService.getPath(user.getId());

        if (path == null) {
            String url = user.getPerfilPhoto();
            if (url == null || url.isBlank()) {
                return null;
            }
            Map<String, Object> google = new LinkedHashMap<>();
            google.put("source", "GOOGLE");
            google.put("url", url);
            google.put("note", "Set when you signed in with Google. The image lives on "
                    + "Google's servers, not ours, so there are no bytes here to give you.");
            return google;
        }

        Map<String, Object> uploaded = new LinkedHashMap<>();
        uploaded.put("source", "UPLOAD");
        uploaded.put("contentType", "image/jpeg");
        uploaded.put("filename", "profile-photo.jpg");
        try {
            byte[] bytes = Files.readAllBytes(path);
            uploaded.put("sizeBytes", bytes.length);
            uploaded.put("base64", Base64.getEncoder().encodeToString(bytes));
            uploaded.put("note", "The photo you uploaded, base64-encoded. Decode it to get "
                    + "the JPEG back.");
        } catch (IOException e) {
            // The rest of the export is worth more than this one field, so a photo that
            // cannot be read does not take the download with it. It does have to say so:
            // reporting the file as absent is the bug this method was written to fix.
            log.error("Could not read the profile photo at {} for user {} while exporting",
                    path, user.getId(), e);
            uploaded.put("base64", null);
            uploaded.put("readError", "Your photo is stored on our server but could not be "
                    + "read while this file was being built. Everything else here is complete. "
                    + "Try the download again.");
        }
        return uploaded;
    }

    /**
     * R8 — routines with their sections, the groups inside them and the days they run.
     *
     * <p>A routine stripped to its name and XP is not a routine: what a person built is the
     * shape — the sections, their times, the order, which habit or task sits in each group.
     * That shape is also the part deletion destroys most completely, since habits and tasks
     * survive in their own sections of this file while the arrangement of them does not.
     *
     * <p>Groups are exported as references ({@code habitId}, {@code taskId}) rather than
     * copies, so a reader joins them against the {@code habits} and {@code tasks} sections
     * instead of reading the same habit spelled out once per routine that uses it. Checks are
     * left out on purpose — those are outcomes, and outcomes are {@code checkHistory}'s job.
     *
     * <p>Read inside the enclosing read-only transaction: sections arrive with the routine
     * through an entity graph, and the groups below them are lazy with {@code @BatchSize(50)},
     * so this walk costs a bounded handful of queries rather than one per section.
     */
    private List<Map<String, Object>> routines(UUID userId) {
        List<DiaryRoutine> routines = diaryRoutineRepository.findAllByUserId(userId);

        return routines.stream().map(r -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", r.getId());
            map.put("name", r.getName());
            map.put("iconId", r.getIconId());
            // DAILY or LIST. This said "DiaryRoutine" for every routine, which is the Java
            // class name and told a reader nothing once LIST routines existed.
            map.put("type", r.getRoutineType());
            map.put("schedule", schedule(r.getSchedule()));
            map.put("progress", xp(r.getXpProgress()));
            map.put("streak", streak(r.getCheckProgress()));
            map.put("sections", r.getRoutineSections().stream().map(this::section).toList());
            return map;
        }).toList();
    }

    private Map<String, Object> section(RoutineSection s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", s.getId());
        map.put("name", s.getName());
        map.put("iconId", s.getIconId());
        map.put("startTime", s.getStartTime());
        map.put("endTime", s.getEndTime());
        map.put("orderIndex", s.getOrderIndex());
        map.put("favorite", s.getFavorite());
        map.put("habits", s.getHabitGroups().stream().map(this::habitGroup).toList());
        map.put("tasks", s.getTaskGroups().stream().map(this::taskGroup).toList());
        return map;
    }

    private Map<String, Object> habitGroup(HabitGroup group) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", group.getId());
        map.put("habitId", group.getHabit() == null ? null : group.getHabit().getId());
        map.put("startTime", group.getStartTime());
        map.put("endTime", group.getEndTime());
        // A LIST routine has no times, so its order is the only thing saying which item comes
        // first. A DAILY one orders by time and carries the number along anyway.
        map.put("orderIndex", group.getOrderIndex());
        return map;
    }

    private Map<String, Object> taskGroup(TaskGroup group) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", group.getId());
        map.put("taskId", group.getTask() == null ? null : group.getTask().getId());
        map.put("startTime", group.getStartTime());
        map.put("endTime", group.getEndTime());
        map.put("orderIndex", group.getOrderIndex());
        return map;
    }

    /**
     * The days a routine runs on. Null when the routine was never scheduled — a real state
     * in this domain, since a routine can be built and left unscheduled.
     */
    private Map<String, Object> schedule(Schedule schedule) {
        if (schedule == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", schedule.getId());
        // Copied, not handed over. days is an @ElementCollection, so what the getter
        // returns is a lazy proxy that dies the moment this read-only transaction
        // closes — and it closes before Jackson writes a single byte. Same failure
        // DiaryRoutineMapper hit with habitGroupChecks, same fix.
        map.put("days", schedule.getDays() == null ? Set.of() : new LinkedHashSet<>(schedule.getDays()));
        return map;
    }

    /** Level and XP, in the same shape everywhere it appears. */
    private Map<String, Object> xp(XpProgress progress) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("xp", progress.getXp());
        map.put("level", progress.getLevel());
        map.put("actualLevelXp", progress.getActualLevelXp());
        map.put("nextLevelXp", progress.getNextLevelXp());
        return map;
    }

    /** Streak counters, likewise. The number a user is proudest of usually lives here. */
    private Map<String, Object> streak(CheckProgress progress) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("currentStreak", progress.getCurrentStreak());
        map.put("bestStreak", progress.getBestStreak());
        map.put("totalCheckIns", progress.getTotalCheckIns());
        map.put("firstCheckInDate", progress.getFirstCheckInDate());
        map.put("lastCheckInDate", progress.getLastCheckInDate());
        return map;
    }

    /**
     * R10 — the per-day outcome history, grouped by the thing it describes, bounded on
     * purpose.
     *
     * <p>The bound is the point. Every other section here has a natural ceiling — a user has
     * so many habits, so many submissions — but this one gains a row per checkable entity per
     * day and never stops, and the whole payload is assembled in memory inside one read-only
     * transaction. An account three years old would put roughly a thousand days times every
     * habit it ever had into a single map. So the window is the most recent
     * {@link CheckHistoryService#MAX_RANGE_DAYS} days, the same cap the history endpoint
     * clamps to, and the export names it: {@code from}, {@code to} and {@code maxRangeDays}
     * are in the payload so a reader can see what was covered instead of assuming the file is
     * everything. Rows outside the window are still stored — nothing here deletes them — and
     * the endpoint can walk further back a window at a time.
     *
     * <p>{@code to} is today in the ACCOUNT's timezone (R15), so the last day in the export
     * is the day the user believes it is.
     *
     * <p>Rows come back ordered by day across every owner type together, so grouping them
     * into first-seen owner order leaves each owner's days ascending without a second sort.
     */
    private Map<String, Object> checkHistory(User user) {
        LocalDate to = UserDateResolver.today(user);
        LocalDate from = to.minusDays(CheckHistoryService.MAX_RANGE_DAYS - 1L);

        List<EntityCheckDay> rows = entityCheckDayRepository
                .findByUserIdAndDayBetweenOrderByDayAsc(user.getId(), from, to);

        Map<String, Map<String, Object>> byOwner = new LinkedHashMap<>();
        for (EntityCheckDay row : rows) {
            String key = row.getOwnerType() + ":" + row.getOwnerId();
            Map<String, Object> owner = byOwner.computeIfAbsent(key, k -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("ownerType", row.getOwnerType());
                created.put("ownerId", row.getOwnerId());
                created.put("days", new ArrayList<Map<String, Object>>());
                return created;
            });

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("day", row.getDay());
            day.put("outcome", row.getOutcome());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> days = (List<Map<String, Object>>) owner.get("days");
            days.add(day);
        }

        Map<String, Object> history = new LinkedHashMap<>();
        history.put("from", from);
        history.put("to", to);
        history.put("maxRangeDays", CheckHistoryService.MAX_RANGE_DAYS);
        history.put("note", "Covers the most recent " + CheckHistoryService.MAX_RANGE_DAYS
                + " days only. Anything older is still stored and readable through the "
                + "check-history endpoint one window at a time.");
        history.put("owners", List.copyOf(byOwner.values()));
        return history;
    }

    /**
     * Pages as plain text (the document format is the editor's business, the words are the
     * person's), the boards that arrange them, flashcards with their schedule and every answer
     * given to them, the sources' details, the study-room chats and what the study room made.
     */
    private Map<String, Object> notebook(UUID userId) {
        Map<String, Object> notebook = new LinkedHashMap<>();
        notebook.put("pages", notebookPageRepository.findByUserId(userId).stream().map(p -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", p.getId());
            map.put("kind", p.getKind());
            map.put("parentId", p.getParentId());
            map.put("title", p.getTitle());
            map.put("status", p.getStatus());
            map.put("text", p.getContentText());
            map.put("icon", p.getIcon());
            // The study room's setup, which the person wrote or chose.
            map.put("studyGoal", p.getStudyGoal());
            map.put("studyScope", p.getStudyScope());
            map.put("updatedAt", p.getUpdatedAt());
            return map;
        }).toList());
        notebook.put("flashcards", notebookCardRepository.findByUserIdOrderByCreatedAtAsc(userId).stream().map(c -> {
            Map<String, Object> map = new LinkedHashMap<>();
            // The id is what flashcardReviews points at.
            map.put("id", c.getId());
            map.put("pageId", c.getPageId());
            map.put("front", c.getFront());
            map.put("back", c.getBack());
            map.put("dueOn", c.getDueOn());
            return map;
        }).toList());
        // Every answer, not just where the schedule ended up: the history of how a card went
        // is the person's record of studying it.
        notebook.put("flashcardReviews", notebookCardReviewRepository.findByUserIdOrderByReviewedAtAsc(userId).stream().map(r -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("cardId", r.getCardId());
            map.put("rating", r.getRating());
            map.put("reviewedOn", r.getReviewDate());
            map.put("reviewedAt", r.getReviewedAt());
            return map;
        }).toList());
        notebook.put("board", board(userId));
        notebook.put("sources", notebookSourceRepository.findByUserIdOrderByCreatedAtAsc(userId).stream().map(s -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("pageId", s.getPageId());
            map.put("kind", s.getKind());
            map.put("title", s.getTitle());
            map.put("url", s.getUrl());
            map.put("addedAt", s.getCreatedAt());
            return map;
        }).toList());
        notebook.put("studyChats", notebookChatMessageRepository.findByUserIdOrderByCreatedAtAsc(userId).stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("pageId", m.getPageId());
            map.put("role", m.getRole());
            map.put("content", m.getContent());
            map.put("at", m.getCreatedAt());
            return map;
        }).toList());
        // Overviews, summaries, study guides and quizzes with the score they got. Generated by
        // a model, but generated for this person out of their own pages, and the quiz results
        // are theirs outright.
        notebook.put("studyOutputs", notebookStudyOutputRepository.findByUserIdOrderByCreatedAtAsc(userId).stream().map(o -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("pageId", o.getPageId());
            map.put("kind", o.getKind());
            map.put("title", o.getTitle());
            map.put("content", o.getContent());
            map.put("score", o.getScore());
            map.put("total", o.getTotal());
            map.put("passedAt", o.getPassedAt());
            map.put("createdAt", o.getCreatedAt());
            return map;
        }).toList());
        // What was asked for, what the model drafted and the ticks: all of it is the person's.
        notebook.put("roadmapDrafts", roadmapDraftService.exportForUser(userId));
        return notebook;
    }

    /**
     * The boards: which pages and sections sit on each one, where, and what links to what.
     *
     * <p>Positions are kept. A board is a layout the person made by dragging things around,
     * the way a routine is an order they chose, and dropping x and y would hand back a pile
     * of cards with the arrangement gone. Two queries, one per table, whatever the size.
     */
    private Map<String, Object> board(UUID userId) {
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("nodes", notebookBoardNodeRepository.findByUserIdOrderByCreatedAtAsc(userId).stream().map(n -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", n.getId());
            map.put("boardPageId", n.getBoardPageId());
            map.put("kind", n.getKind());
            map.put("pageId", n.getPageId());
            map.put("label", n.getLabel());
            map.put("x", n.getX());
            map.put("y", n.getY());
            map.put("width", n.getWidth());
            map.put("height", n.getHeight());
            return map;
        }).toList());
        board.put("edges", notebookBoardEdgeRepository.findByUserId(userId).stream().map(e -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("boardPageId", e.getBoardPageId());
            map.put("fromNodeId", e.getSourceNodeId());
            map.put("toNodeId", e.getTargetNodeId());
            return map;
        }).toList());
        return board;
    }

    /**
     * Completed focus cycles and the micro-tasks written during them.
     *
     * <p>Both point at a routine item by {@code itemGroupId}, the same id the routines section
     * gives each habit or task inside a section, so a reader joins them there. The id comes
     * off the foreign key without loading the group, so this stays two queries.
     */
    private Map<String, Object> focus(UUID userId) {
        Map<String, Object> focus = new LinkedHashMap<>();
        focus.put("cycles", focusCycleRepository.findAllForExport(userId).stream().map(c -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("date", c.getCycleDate());
            map.put("kind", c.getKind());
            map.put("minutes", c.getMinutes());
            map.put("startedAt", c.getStartedAt());
            map.put("endedAt", c.getEndedAt());
            map.put("itemGroupId", c.getItemGroup() == null ? null : c.getItemGroup().getId());
            map.put("notebookPageId", c.getNotebookPageId());
            return map;
        }).toList());
        focus.put("microTasks", focusMicroTaskRepository.findAllForExport(userId).stream().map(t -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("date", t.getTaskDate());
            map.put("itemGroupId", t.getItemGroup().getId());
            map.put("name", t.getName());
            map.put("pinned", t.isPinned());
            map.put("doneAt", t.getDoneAt());
            map.put("orderIndex", t.getOrderIndex());
            return map;
        }).toList());
        return focus;
    }

    /**
     * The outside accounts that can sign in to this one.
     *
     * <p>The provider's subject goes in. It is how that provider names this person, which
     * makes it personal data under the same rule as an email address, and it is not a
     * credential: signing in still takes a token the provider signs. Leaving it out would hide
     * the one value that says which account at the provider is linked. No token of any kind is
     * stored here, so none can leave.
     */
    private List<Map<String, Object>> linkedSignIns(UUID userId) {
        return federatedIdentityRepository.findAllByUserId(userId).stream().map(f -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("issuer", f.getIssuer());
            map.put("subject", f.getSubject());
            map.put("emailAtLink", f.getEmailAtLink());
            map.put("linkedAt", f.getCreatedAt());
            map.put("lastLoginAt", f.getLastLoginAt());
            return map;
        }).toList();
    }
}

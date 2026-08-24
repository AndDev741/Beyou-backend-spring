package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.checkday.CheckHistoryService;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.common.CheckProgress;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.feedback.FeedbackService;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferences;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferencesRepository;
import beyou.beyouapp.backend.security.AuthenticatedUser;
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

        // Say out loud what a reader will not find here, so the file can be trusted
        // as a whole rather than spot-checked. Deletion takes these too.
        Map<String, Object> omitted = new LinkedHashMap<>();
        omitted.put("routineSnapshots", "The per-day frozen copy of each routine, one row per "
                + "routine per day, each carrying a full copy of that day's structure. The "
                + "outcomes they record are in checkHistory, in bounded form; the copies "
                + "themselves would grow this file without limit.");
        omitted.put("credentials", "Password hash, refresh tokens and any pending "
                + "verification or reset tokens. Nothing here is useful to you and all of it "
                + "is dangerous in a file.");
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
            map.put("type", "DiaryRoutine");
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
        return map;
    }

    private Map<String, Object> taskGroup(TaskGroup group) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", group.getId());
        map.put("taskId", group.getTask() == null ? null : group.getTask().getId());
        map.put("startTime", group.getStartTime());
        map.put("endTime", group.getEndTime());
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
}

package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.domain.routine.RoutineType;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalTime;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.mood.MoodService;
import beyou.beyouapp.backend.domain.mood.dto.SetMoodLevelDTO;
import beyou.beyouapp.backend.domain.mood.dto.UpsertMoodEntryDTO;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.briefing.NarrativeStatus;
import beyou.beyouapp.backend.domain.focus.CycleKind;
import beyou.beyouapp.backend.domain.goal.GoalService;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.domain.notebook.card.CardRating;
import beyou.beyouapp.backend.domain.notebook.study.StudyOutputKind;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineItemRequestDTO;
import beyou.beyouapp.backend.notification.engagement.NudgeKind;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleService;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.HabitGroupDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.TaskGroupDTO;
import beyou.beyouapp.backend.domain.aiAgent.chat.AgentMessageService;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.AgentMessageDTO;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.AgentSegment;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the download actually contains, for an account that has been used.
 *
 * The export sits directly beside the delete button and is offered as the thing you
 * take before leaving, so the standard it has to meet is not "the endpoint returns
 * 200" — {@code UserExportControllerTest} covers that against a mocked service, which
 * means it would go on passing if this class returned an empty map. The standard is
 * that a person who takes the file and then deletes the account has not silently lost
 * anything they were told they were keeping.
 *
 * It failed that once already: routines, the days they run on and every XP and streak
 * counter in the account were absent, while the copy beside the button named routines
 * and XP by name as things deletion would destroy. So the assertions here walk into
 * the structure rather than checking a key exists — a routine reduced to its name is
 * the shape the bug had.
 */
class UserExportCompletenessIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "export-completeness@beyou.test";

    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired UserExportService exportService;
    @Autowired CategoryService categoryService;
    @Autowired HabitService habitService;
    @Autowired TaskService taskService;
    @Autowired DiaryRoutineService diaryRoutineService;
    @Autowired ScheduleService scheduleService;
    @Autowired ChatService chatService;
    @Autowired AgentMessageService agentMessageService;
    @Autowired PhotoStorageService photoStorageService;
    @Autowired MoodService moodService;
    @Autowired GoalService goalService;
    @Autowired JdbcTemplate jdbc;

    /**
     * Where each table that points at a user ends up in the file: a section, or a key under
     * {@code notIncluded} that says why it stayed out. Read against the live schema by
     * {@link #everyTableThatPointsAtAUserIsInTheFileOrSaysWhyNot()}.
     */
    private static final Map<String, String> WHERE_EACH_TABLE_GOES = Map.ofEntries(
            entry("categories", "categories"),
            entry("habits", "habits"),
            entry("tasks", "tasks"),
            entry("goals", "goals"),
            entry("routines", "routines"),
            entry("chats", "agentChats"),
            entry("feedback", "feedback"),
            entry("feedback_reply", "feedback"),
            entry("entity_check_day", "checkHistory"),
            entry("mood_entries", "moodEntries"),
            entry("notification_preferences", "profile.engagementEmails"),
            entry("notification_sends", "engagementEmailsSent"),
            entry("federated_identities", "profile.linkedSignIns"),
            entry("focus_cycles", "focus.cycles"),
            entry("focus_micro_tasks", "focus.microTasks"),
            entry("daily_briefing", "dailyBriefings"),
            entry("notebook_pages", "notebook.pages"),
            entry("notebook_board_nodes", "notebook.board.nodes"),
            entry("notebook_board_edges", "notebook.board.edges"),
            entry("notebook_cards", "notebook.flashcards"),
            entry("notebook_card_reviews", "notebook.flashcardReviews"),
            entry("notebook_sources", "notebook.sources"),
            entry("notebook_study_outputs", "notebook.studyOutputs"),
            entry("notebook_chat_messages", "notebook.studyChats"),
            entry("notebook_roadmap_drafts", "notebook.roadmapDrafts"),
            entry("routine_snapshot", "notIncluded.routineSnapshots"),
            entry("notebook_source_chunks", "notIncluded.notebookSourceText"),
            entry("entity_xp_day", "notIncluded.xpHistory"),
            entry("refresh_tokens", "notIncluded.credentials"),
            entry("password_reset_tokens", "notIncluded.credentials"),
            entry("account_deletion_codes", "notIncluded.credentials"));

    private User user;

    @BeforeEach
    void setUp() {
        userRepository.findByEmail(EMAIL).ifPresent(existing -> userService.deleteUser(existing));

        User fresh = new User();
        fresh.setName("someone packing up");
        fresh.setEmail(EMAIL);
        fresh.setPassword("placeholder");
        fresh.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        user = userRepository.saveAndFlush(fresh);

        // The export reads whoever is authenticated, so the test has to be them.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        photoStorageService.delete(user.getId());
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the export carries the uploaded photo as bytes a reader can decode back to an image")
    @SuppressWarnings("unchecked")
    void exportsTheUploadedPhotoAsBytes() throws Exception {
        // The account this used to be wrong for: uploaded a photo, never signed in with
        // Google, so perfilPhoto is null and the file on disk is the only copy there is.
        BufferedImage face = new BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(face, "jpg", jpeg);
        photoStorageService.store(user.getId(),
                new MockMultipartFile("file", "face.jpg", "image/jpeg", jpeg.toByteArray()));

        Map<String, Object> export = exportService.exportUserData();
        Map<String, Object> profile = (Map<String, Object>) export.get("profile");
        Map<String, Object> photo = (Map<String, Object>) profile.get("photo");

        assertThat(photo)
                .as("this field read null for every account that uploaded a photo instead of "
                        + "signing in with Google, because it was reading the column the "
                        + "upload never writes")
                .isNotNull();
        assertThat(photo.get("source")).isEqualTo("UPLOAD");
        assertThat(photo.get("contentType")).isEqualTo("image/jpeg");
        assertThat(photo.get("readError")).as("the bytes were readable, so say nothing about errors").isNull();

        // Decoded rather than merely present: a non-empty string in this field could
        // still be a path, a URL or a placeholder, and none of those is the photo.
        byte[] decoded = Base64.getDecoder().decode((String) photo.get("base64"));
        assertThat(decoded).hasSize((int) photo.get("sizeBytes"));
        assertThat(ImageIO.read(new ByteArrayInputStream(decoded)))
                .as("what comes out of the file has to be an image again, or the export is "
                        + "carrying something that only looks like one")
                .isNotNull();
    }

    @Test
    @DisplayName("a Google avatar is exported as its URL, and says the bytes were never ours")
    @SuppressWarnings("unchecked")
    void exportsAGoogleAvatarAsALink() {
        user.setGoogleAccount(true);
        user.setPerfilPhoto("https://lh3.googleusercontent.com/a/seeded-avatar");
        user = userRepository.saveAndFlush(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));

        Map<String, Object> export = exportService.exportUserData();
        Map<String, Object> profile = (Map<String, Object>) export.get("profile");
        Map<String, Object> photo = (Map<String, Object>) profile.get("photo");

        assertThat(photo).isNotNull();
        assertThat(photo.get("source")).isEqualTo("GOOGLE");
        assertThat(photo.get("url")).isEqualTo("https://lh3.googleusercontent.com/a/seeded-avatar");
        assertThat(photo.get("base64"))
                .as("we hold a link, not a file — inventing bytes here would be worse than "
                        + "the omission this fixed")
                .isNull();
    }

    @Test
    @DisplayName("an account with no photo exports null, so the absence reads as an answer")
    @SuppressWarnings("unchecked")
    void exportsNullWhenThereIsNoPhoto() {
        Map<String, Object> export = exportService.exportUserData();
        Map<String, Object> profile = (Map<String, Object>) export.get("profile");

        assertThat(profile).containsKey("photo");
        assertThat(profile.get("photo")).isNull();
    }

    @Test
    @DisplayName("the export carries the routine, the days it runs on and every progress counter")
    @SuppressWarnings("unchecked")
    void exportsTheStructureAndTheProgress() {
        UUID userId = user.getId();

        categoryService.createCategory(new CategoryRequestDTO(
                "Health", "lucide:heart", "seeded", ExperienceLevel.BEGINNER), userId);
        UUID categoryId = categoryService.getAllCategories(userId).get(0).id();

        habitService.createHabit(new CreateHabitDTO("Drink water", "seeded", "stay hydrated",
                "lucide:droplet", 3, 2, List.of(categoryId), ExperienceLevel.BEGINNER), userId);
        UUID habitId = habitService.getHabits(userId).get(0).id();

        taskService.createTask(new CreateTaskRequestDTO("Tidy the desk", "seeded",
                "lucide:broom", 2, 2, List.of(categoryId), false), userId);
        UUID taskId = taskService.getAllTasks(userId).get(0).id();

        diaryRoutineService.createDiaryRoutine(new DiaryRoutineRequestDTO(
                "Morning", "lucide:sun", RoutineType.DAILY, List.of(new RoutineSectionRequestDTO(
                        null, "Wake up", "lucide:sunrise", LocalTime.of(7, 0), LocalTime.of(8, 0),
                        List.of(new TaskGroupDTO(null, taskId, LocalTime.of(7, 30), LocalTime.of(7, 40), null)),
                        List.of(new HabitGroupDTO(null, habitId, LocalTime.of(7, 0), LocalTime.of(7, 10), null)),
                        false)), List.of()), userId);
        UUID routineId = diaryRoutineService.getAllDiaryRoutines(userId).get(0).id();

        scheduleService.create(new CreateScheduleDTO(
                Set.of(WeekDay.Monday, WeekDay.Wednesday), routineId), userId);

        Map<String, Object> export = exportService.exportUserData();

        List<Map<String, Object>> routines = (List<Map<String, Object>>) export.get("routines");
        assertThat(routines).as("a routine the account owns has to appear at all").hasSize(1);

        Map<String, Object> routine = routines.get(0);
        assertThat(routine.get("name")).isEqualTo("Morning");
        assertThat(routine.get("progress")).isNotNull();
        assertThat(routine.get("streak")).isNotNull();

        // The days, which are what turn a list of habits into a routine that happens.
        Map<String, Object> schedule = (Map<String, Object>) routine.get("schedule");
        assertThat(schedule).as("a scheduled routine must carry its days").isNotNull();
        assertThat((Set<WeekDay>) schedule.get("days"))
                .containsExactlyInAnyOrder(WeekDay.Monday, WeekDay.Wednesday);

        // The arrangement: habits and tasks survive in their own sections of the file,
        // but which one sits in which section at what time lives only here.
        List<Map<String, Object>> sections = (List<Map<String, Object>>) routine.get("sections");
        assertThat(sections).hasSize(1);
        Map<String, Object> section = sections.get(0);
        assertThat(section.get("name")).isEqualTo("Wake up");
        assertThat(section.get("startTime")).isEqualTo(LocalTime.of(7, 0));

        List<Map<String, Object>> habitGroups = (List<Map<String, Object>>) section.get("habits");
        assertThat(habitGroups).hasSize(1);
        assertThat(habitGroups.get(0).get("habitId"))
                .as("groups reference the habits section rather than copying it")
                .isEqualTo(habitId);

        List<Map<String, Object>> taskGroups = (List<Map<String, Object>>) section.get("tasks");
        assertThat(taskGroups).hasSize(1);
        assertThat(taskGroups.get(0).get("taskId")).isEqualTo(taskId);

        // Levels and streaks: the numbers a person is likeliest to want a record of.
        Map<String, Object> profile = (Map<String, Object>) export.get("profile");
        assertThat((Map<String, Object>) profile.get("progress")).containsKeys("xp", "level");
        assertThat((Map<String, Object>) profile.get("streak")).containsKeys("currentStreak", "bestStreak");
        assertThat(profile.get("timezone")).isEqualTo(user.getTimezone());

        List<Map<String, Object>> habits = (List<Map<String, Object>>) export.get("habits");
        assertThat((Map<String, Object>) habits.get(0).get("progress")).containsKeys("xp", "level");
        assertThat((Map<String, Object>) habits.get(0).get("streak")).containsKey("bestStreak");

        List<Map<String, Object>> tasks = (List<Map<String, Object>>) export.get("tasks");
        assertThat((Map<String, Object>) tasks.get(0).get("streak")).containsKey("bestStreak");

        List<Map<String, Object>> categories = (List<Map<String, Object>>) export.get("categories");
        assertThat((Map<String, Object>) categories.get(0).get("progress")).containsKeys("xp", "level");

        // Whatever stays out stays out on the record, so the file can be read as a
        // whole instead of spot-checked against the app.
        assertThat((Map<String, Object>) export.get("notIncluded"))
                .containsKeys("routineSnapshots", "credentials")
                .doesNotContainKey("agentChat");

        userService.deleteUser(user);
    }

    /**
     * Assistant conversations were the one thing named in {@code notIncluded}, which
     * made them the one thing nobody could get a copy of — and they are the part of
     * the account that actually left the server for a third-party model. What the
     * assistant was told, and the notes it wrote back, are data about its user.
     */
    @Test
    @DisplayName("the export carries the assistant conversations, their transcript and the notes the model kept")
    @SuppressWarnings("unchecked")
    void exportsTheAssistantConversations() {
        UUID userId = user.getId();

        UUID chatId = chatService.createChat("Planning my week", userId).id();
        chatService.updateChatContext("Prefers mornings", chatId, userId);
        chatService.updateGlobalContext("Training for a half marathon", userId);
        agentMessageService.recordTurn(chatId, "Build me a morning routine",
                List.of(AgentSegment.text("Done — here it is.")), "gemini");

        // The global note is written straight to the row, so the instance parked in the
        // security context by setUp() is now stale. A real request never sees that —
        // SecurityFilter loads the user fresh every time — so reload here rather than
        // asserting against a copy from before the write.
        user = userRepository.findById(userId).orElseThrow();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));

        Map<String, Object> export = exportService.exportUserData();

        List<Map<String, Object>> chats = (List<Map<String, Object>>) export.get("agentChats");
        assertThat(chats).hasSize(1);
        assertThat(chats.get(0))
                .containsEntry("title", "Planning my week")
                .containsEntry("assistantNotesAboutThisChat", "Prefers mornings");

        // The turn itself, not just a count: a chat reduced to its title is the shape
        // the omission had.
        List<AgentMessageDTO> messages = (List<AgentMessageDTO>) chats.get(0).get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("USER");
        assertThat(messages.get(0).segments().get(0).text()).isEqualTo("Build me a morning routine");
        assertThat(messages.get(1).segments().get(0).text()).isEqualTo("Done — here it is.");

        // The cross-chat note the model keeps about the person lives on their row, so
        // it travels with the profile rather than with any one conversation.
        assertThat((Map<String, Object>) export.get("profile"))
                .containsEntry("assistantNotesAboutYou", "Training for a half marathon");

        userService.deleteUser(user);
    }

    /**
     * The journal is the most personal thing the account holds, and deleting the account destroys
     * it. So an export that carried the level but not the words would be exactly the failure this
     * class exists for: the person keeps a file, deletes the account, and what they actually wrote
     * is gone.
     */
    @Test
    @DisplayName("the export carries every mood entry with the words the person wrote, not just the level")
    @SuppressWarnings("unchecked")
    void exportsTheJournalInFull() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        moodService.upsert(user, today, new UpsertMoodEntryDTO(4, "Slept badly but the talk went well."));
        moodService.setLevel(user, today.minusDays(1), new SetMoodLevelDTO(2));

        Map<String, Object> export = exportService.exportUserData();

        List<Map<String, Object>> entries = (List<Map<String, Object>>) export.get("moodEntries");
        assertThat(entries).as("a journalled account must have its entries in the file").hasSize(2);

        Map<String, Object> written = entries.get(0);
        assertThat(written.get("date")).isEqualTo(today);
        assertThat(written.get("mood")).isEqualTo(4);
        assertThat(written.get("note"))
                .as("the words themselves, not a summary and not a flag that words existed")
                .isEqualTo("Slept badly but the talk went well.");

        assertThat(entries.get(1).get("note")).as("a day with only a level is still a day").isNull();
    }

    /**
     * Everything that shipped after the export was last made whole: the goal tree, LIST
     * routines, focus mode, linked sign-ins, the morning briefing, the nudge mails, and the
     * parts of the notebook that are not pages. Each of those reached production with no line
     * in this file, and the class comment above says what that costs.
     */
    @Test
    @DisplayName("the export carries the goal tree, the routine shape, focus, sign-ins, briefings and the whole notebook")
    @SuppressWarnings("unchecked")
    void exportsWhatShippedSinceTheLastAudit() {
        UUID userId = user.getId();

        categoryService.createCategory(new CategoryRequestDTO(
                "Languages", "lucide:book", "seeded", ExperienceLevel.BEGINNER), userId);
        UUID categoryId = categoryService.getAllCategories(userId).get(0).id();
        habitService.createHabit(new CreateHabitDTO("Drink water", "seeded", "stay hydrated",
                "lucide:droplet", 3, 2, List.of(categoryId), ExperienceLevel.BEGINNER), userId);
        UUID habitId = habitService.getHabits(userId).get(0).id();
        taskService.createTask(new CreateTaskRequestDTO("Tidy the desk", "seeded",
                "lucide:broom", 2, 2, List.of(categoryId), false), userId);
        UUID taskId = taskService.getAllTasks(userId).get(0).id();

        // A LIST routine: no times anywhere, so the order is the only shape it has.
        diaryRoutineService.createDiaryRoutine(new DiaryRoutineRequestDTO(
                "Errands", "lucide:list", RoutineType.LIST, null, List.of(
                        new RoutineItemRequestDTO(null, habitId, null),
                        new RoutineItemRequestDTO(null, null, taskId))), userId);
        UUID routineId = diaryRoutineService.getAllDiaryRoutines(userId).get(0).id();

        UUID parentGoalId = createGoal("Speak Spanish", null, categoryId);
        UUID childGoalId = createGoal("Finish the A1 book", parentGoalId, categoryId);

        UUID itemGroupId = jdbc.queryForObject("SELECT hg.id FROM habit_groups hg "
                + "JOIN habits h ON h.id = hg.habit_id WHERE h.user_id = ?", UUID.class, userId);
        UserOwnedRows.Seeded seeded = UserOwnedRows.seed(jdbc, userId, itemGroupId, routineId,
                habitId, categoryId);

        Map<String, Object> export = exportService.exportUserData();

        // The tree. Flat, a sub-goal reads like a goal of its own.
        List<Map<String, Object>> goals = (List<Map<String, Object>>) export.get("goals");
        Map<UUID, Object> parentOf = new HashMap<>();
        goals.forEach(goal -> parentOf.put((UUID) goal.get("id"), goal.get("parentId")));
        assertThat(parentOf).containsEntry(childGoalId, parentGoalId);
        assertThat(parentOf.get(parentGoalId)).as("the top of the tree has no parent").isNull();

        // The routine says what it is, and the items say their order.
        Map<String, Object> routine = ((List<Map<String, Object>>) export.get("routines")).get(0);
        assertThat(routine.get("type"))
                .as("this used to say DiaryRoutine for every routine, LIST or not")
                .isEqualTo(RoutineType.LIST);
        Map<String, Object> listSection = ((List<Map<String, Object>>) routine.get("sections")).get(0);
        Map<String, Object> habitItem = ((List<Map<String, Object>>) listSection.get("habits")).get(0);
        Map<String, Object> taskItem = ((List<Map<String, Object>>) listSection.get("tasks")).get(0);
        assertThat(habitItem.get("orderIndex")).isEqualTo(0);
        assertThat(taskItem.get("orderIndex")).isEqualTo(1);

        // Focus: the cycle and the micro-task, both pointing at the routine item they ran on.
        Map<String, Object> focus = (Map<String, Object>) export.get("focus");
        assertThat(focus).as("focus mode had no section at all").isNotNull();
        Map<String, Object> cycle = ((List<Map<String, Object>>) focus.get("cycles")).get(0);
        assertThat(cycle.get("kind")).isEqualTo(CycleKind.POMODORO);
        assertThat(cycle.get("minutes")).isEqualTo(25);
        assertThat(cycle.get("itemGroupId")).isEqualTo(itemGroupId);
        assertThat(cycle.get("notebookPageId")).isEqualTo(seeded.pageId());
        Map<String, Object> microTask = ((List<Map<String, Object>>) focus.get("microTasks")).get(0);
        assertThat(microTask.get("name")).isEqualTo("fill the bottle");
        assertThat(microTask.get("itemGroupId")).isEqualTo(itemGroupId);

        // Who else can sign in to this account.
        Map<String, Object> profile = (Map<String, Object>) export.get("profile");
        Map<String, Object> signIn = ((List<Map<String, Object>>) profile.get("linkedSignIns")).get(0);
        assertThat(signIn.get("issuer")).isEqualTo("https://id.example.test");
        assertThat(signIn.get("subject")).isEqualTo("subject-" + userId + "-0");
        assertThat(signIn.get("emailAtLink")).isEqualTo("linked@example.test");

        // The briefing's words, read back the way the dialog reads them.
        Map<String, Object> briefing = ((List<Map<String, Object>>) export.get("dailyBriefings")).get(0);
        assertThat(briefing.get("date")).isEqualTo(seeded.day());
        assertThat(briefing.get("status")).isEqualTo(NarrativeStatus.READY);
        assertThat((List<String>) briefing.get("todayLines")).containsExactly("Two habits left.");
        assertThat((List<String>) briefing.get("yesterdayLines")).containsExactly("A full day.");

        Map<String, Object> mail = ((List<Map<String, Object>>) export.get("engagementEmailsSent")).get(0);
        assertThat(mail.get("kind")).isEqualTo(NudgeKind.STREAK_RECORD_AT_RISK);
        assertThat(mail.get("sentOn")).isEqualTo(seeded.day());

        // The notebook beyond its pages.
        Map<String, Object> notebook = (Map<String, Object>) export.get("notebook");
        Map<String, Object> board = (Map<String, Object>) notebook.get("board");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) board.get("nodes");
        assertThat(nodes).hasSize(2);
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo(seeded.sectionNodeId());
            assertThat(node.get("label")).isEqualTo("Grammar");
            assertThat(node.get("x")).isEqualTo(10.0);
        });
        Map<String, Object> edge = ((List<Map<String, Object>>) board.get("edges")).get(0);
        assertThat(edge.get("fromNodeId")).isEqualTo(seeded.sectionNodeId());
        assertThat(edge.get("toNodeId")).isEqualTo(seeded.pageNodeId());

        Map<String, Object> card = ((List<Map<String, Object>>) notebook.get("flashcards")).get(0);
        assertThat(card.get("id")).as("reviews point at it").isEqualTo(seeded.cardId());
        Map<String, Object> review = ((List<Map<String, Object>>) notebook.get("flashcardReviews")).get(0);
        assertThat(review.get("cardId")).isEqualTo(seeded.cardId());
        assertThat(review.get("rating")).isEqualTo(CardRating.GOOD);

        Map<String, Object> output = ((List<Map<String, Object>>) notebook.get("studyOutputs")).get(0);
        assertThat(output.get("kind")).isEqualTo(StudyOutputKind.QUIZ);
        assertThat(output.get("score")).isEqualTo(4);
        assertThat(output.get("total")).isEqualTo(5);

        assertThat((Map<String, Object>) export.get("notIncluded"))
                .containsKeys("routineSnapshots", "notebookSourceText", "xpHistory",
                        "dailyBriefingFacts", "credentials");

        userService.deleteUser(user);
    }

    /**
     * The guard for the next feature.
     *
     * <p>The export promises that whatever it leaves out is named under {@code notIncluded}.
     * Four features in a row broke that promise without anyone noticing, because nothing
     * connected "a new table that belongs to a user" to "this file". This does: it reads every
     * table pointing at {@code users} from the live schema and fails for any that has no
     * section here and no entry under {@code notIncluded}.
     */
    @Test
    @DisplayName("every table that points at a user is either in the file or named under notIncluded")
    @SuppressWarnings("unchecked")
    void everyTableThatPointsAtAUserIsInTheFileOrSaysWhyNot() {
        Set<String> unaccounted = new TreeSet<>(UserOwnedRows.tablesPointingAtUsers(jdbc));
        unaccounted.removeAll(WHERE_EACH_TABLE_GOES.keySet());
        assertThat(unaccounted)
                .as("These tables hold a user's data and the export says nothing about them. "
                        + "Add a section to UserExportService, or a notIncluded entry saying why "
                        + "not, and map the table in WHERE_EACH_TABLE_GOES.")
                .isEmpty();

        // And every place the map names really exists in the file, even for an empty account.
        Map<String, Object> export = exportService.exportUserData();
        WHERE_EACH_TABLE_GOES.forEach((table, path) -> {
            Object node = export;
            for (String key : path.split("\\.")) {
                assertThat(node).as("%s: %s is not an object in the export", table, path)
                        .isInstanceOf(Map.class);
                assertThat((Map<String, Object>) node)
                        .as("%s should land at %s", table, path)
                        .containsKey(key);
                node = ((Map<String, Object>) node).get(key);
            }
        });
    }

    private UUID createGoal(String name, UUID parentId, UUID categoryId) {
        goalService.createGoal(new CreateGoalRequestDTO(
                name, null, "lucide:flag", 10.0, "chapters", 0.0, List.of(categoryId), null,
                LocalDate.now(ZoneOffset.UTC), LocalDate.now(ZoneOffset.UTC).plusMonths(2),
                GoalStatus.NOT_STARTED, GoalTerm.SHORT_TERM, parentId), user.getId());
        return goalService.getAllGoals(user.getId()).stream()
                .filter(goal -> goal.name().equals(name))
                .findFirst().orElseThrow().id();
    }
}

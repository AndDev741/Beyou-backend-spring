package beyou.beyouapp.backend.performance;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.HibernateStatistics;
import beyou.beyouapp.backend.domain.aiAgent.chat.AgentMessageService;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
import beyou.beyouapp.backend.domain.aiAgent.chat.dto.AgentSegment;
import beyou.beyouapp.backend.domain.feedback.FeedbackCategory;
import beyou.beyouapp.backend.domain.feedback.FeedbackReplyService;
import beyou.beyouapp.backend.domain.feedback.FeedbackService;
import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackReplyRequestDTO;
import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackRequestDTO;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleService;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserExportService;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The data export must not get slower the more of the app someone has used.
 *
 * <p>It did. Three separate reads walked a list and hit the database once per element:
 * the transcript of every assistant conversation, the attachments and replies on every
 * feedback submission, and — twice over — the schedule behind every routine. Measured,
 * that was six extra round trips per unit of history, so the export cost grew linearly
 * with the size of the account. The direction is what made it worth fixing rather than
 * noting: this endpoint sits next to the delete button and is the thing a person takes
 * on their way out, which means the accounts that pay the most for it are the ones with
 * the most to lose, and a heavy user's export is the one likeliest to time out.
 *
 * <p>The guard is a slope, not a ceiling. Asserting "under N statements" only says the
 * fixture is small; it passes just as happily with the N+1 back if someone shrinks the
 * seed. So this seeds the same account twice, at {@link #SMALL} and {@link #LARGE}, and
 * asserts the difference stays flat. With the per-element reads restored the gap is
 * about {@code 6 * (LARGE - SMALL)} and this fails loudly.
 *
 * <p>Content is asserted alongside the count, because collapsing the queries is only
 * correct if the same data comes back — a batch read that silently dropped a chat's
 * messages would satisfy any query-count assertion ever written.
 *
 * <p>One cost stays per chat and is not counted here: conversations older than
 * {@code agent_message} are read from Spring AI's ChatMemory, which is JdbcTemplate and
 * therefore invisible to Hibernate's statistics, and whose API takes one conversation at
 * a time. That set only shrinks.
 */
class UserExportQueryCountTest extends AbstractIntegrationTest {

    /**
     * Two sizes far enough apart that a one-off query can't be mistaken for a slope.
     * {@link #LARGE} is seven because a routine's schedule takes a weekday away from
     * whichever routine held it, so seven is as many simultaneously-scheduled routines
     * as the domain allows — and every routine has to keep its days for the content
     * assertions below to mean anything.
     */
    private static final int SMALL = 2;
    private static final int LARGE = 7;

    /**
     * The most a bigger account may cost. Zero is the real expectation; the slack
     * absorbs a batch boundary, not a per-element read — six per unit would be 60.
     */
    private static final long ALLOWED_GROWTH = 2;

    @Autowired EntityManagerFactory emf;
    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired UserExportService exportService;
    @Autowired ChatService chatService;
    @Autowired AgentMessageService agentMessageService;
    @Autowired FeedbackService feedbackService;
    @Autowired FeedbackReplyService feedbackReplyService;
    @Autowired DiaryRoutineService diaryRoutineService;
    @Autowired ScheduleService scheduleService;

    private User current;

    @BeforeEach
    void resetUsers() {
        for (int n : new int[]{SMALL, LARGE}) {
            userRepository.findByEmail(email(n)).ifPresent(userService::deleteUser);
        }
    }

    @AfterEach
    void tearDown() {
        if (current != null) {
            userService.deleteUser(current);
            current = null;
        }
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("export cost stays flat as the account grows — no per-chat, per-submission or per-routine query")
    void exportDoesNotScaleWithAccountSize() {
        long small = exportStatementsFor(SMALL);
        long large = exportStatementsFor(LARGE);
        long growth = large - small;

        System.out.printf("[export N+1 guard] %d-of-each → %d statements, %d-of-each → %d statements (growth %d)%n",
                SMALL, small, LARGE, large, growth);

        assertThat(growth)
                .as("""
                        The export gained %d statements for %d more of each thing. Flat is the \
                        expectation — a per-element read would show roughly %d. Something in \
                        exportUserData is querying inside a loop again.""",
                        growth, LARGE - SMALL, 6L * (LARGE - SMALL))
                .isLessThanOrEqualTo(ALLOWED_GROWTH);
    }

    /**
     * Seeds an account with {@code n} of every collection the export walks, runs the
     * export, and returns what it cost — having first checked it came back whole.
     */
    @SuppressWarnings("unchecked")
    private long exportStatementsFor(int n) {
        current = seedUser(n);
        UUID userId = current.getId();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(current, null, current.getAuthorities()));

        // Conversations that have a stored transcript, and conversations that do not.
        // The empty ones matter: they take the legacy model-memory path, and a batch
        // read that forgot them would drop the chat rather than slow it down.
        for (int i = 0; i < n; i++) {
            UUID chatId = chatService.createChat("chat-" + i, userId).id();
            agentMessageService.recordTurn(chatId, "question " + i,
                    List.of(AgentSegment.text("answer " + i)), "test-provider");
        }
        for (int i = 0; i < n; i++) {
            chatService.createChat("never used " + i, userId);
        }

        // Submissions, each with a reply, so the reply batch is actually exercised.
        for (int i = 0; i < n; i++) {
            UUID feedbackId = feedbackService.submitFeedback(new CreateFeedbackRequestDTO(
                    FeedbackCategory.BUG, "something is broken " + i, null), userId).id();
            feedbackReplyService.reply(feedbackId, userId,
                    new CreateFeedbackReplyRequestDTO("looking into it " + i));
        }

        // Routines, each scheduled — the schedule and the days it holds were two of
        // the six per-element reads.
        for (int i = 0; i < n; i++) {
            diaryRoutineService.createDiaryRoutine(new DiaryRoutineRequestDTO(
                    "routine-" + i, "lucide:sun",
                    List.of(new RoutineSectionRequestDTO(null, "section", "lucide:sunrise",
                            LocalTime.of(7, 0), LocalTime.of(8, 0), List.of(), List.of(), false))),
                    userId);
        }
        List<UUID> routineIds = diaryRoutineService.getAllDiaryRoutines(userId).stream()
                .map(routine -> routine.id()).toList();
        for (int i = 0; i < routineIds.size(); i++) {
            scheduleService.create(
                    new CreateScheduleDTO(Set.of(WeekDay.values()[i]), routineIds.get(i)), userId);
        }

        var stats = new HibernateStatistics(emf);
        Map<String, Object> export = exportService.exportUserData();
        long statements = stats.statementCount();

        // Fewer queries is only an improvement if the file still holds everything.
        List<Map<String, Object>> chats = (List<Map<String, Object>>) export.get("agentChats");
        assertThat(chats).as("every conversation, used or not, belongs in the export").hasSize(n * 2);
        assertThat(chats.stream()
                .filter(chat -> !((List<?>) chat.get("messages")).isEmpty()))
                .as("the conversations that were actually used must carry their transcript")
                .hasSize(n);

        List<Map<String, Object>> feedback = (List<Map<String, Object>>) export.get("feedback");
        assertThat(feedback).hasSize(n);
        assertThat(feedback).allSatisfy(submission ->
                assertThat((List<?>) submission.get("replies"))
                        .as("a submission that was answered must export the answer")
                        .hasSize(1));

        List<Map<String, Object>> routines = (List<Map<String, Object>>) export.get("routines");
        assertThat(routines).hasSize(n);
        assertThat(routines).allSatisfy(routine -> {
            Map<String, Object> schedule = (Map<String, Object>) routine.get("schedule");
            assertThat(schedule).as("a scheduled routine must still carry its schedule").isNotNull();
            assertThat((Set<WeekDay>) schedule.get("days"))
                    .as("and the day it runs on").hasSize(1);
        });

        userService.deleteUser(current);
        current = null;
        SecurityContextHolder.clearContext();
        return statements;
    }

    private static String email(int n) {
        return "export-query-count-" + n + "@beyou.test";
    }

    private User seedUser(int n) {
        User user = new User();
        user.setName("a well-used account");
        user.setEmail(email(n));
        user.setPassword("placeholder");
        user.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        return userRepository.saveAndFlush(user);
    }
}

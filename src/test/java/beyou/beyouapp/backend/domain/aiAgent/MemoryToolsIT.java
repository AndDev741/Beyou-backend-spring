package beyou.beyouapp.backend.domain.aiAgent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainFactKind;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainFactOrigin;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainNoteOrigin;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainNotes;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainStore;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainSubjectType;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainFactDTO;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * The three memory tools that replaced {@code updateGlobalContext}, against the real store and
 * a real Postgres, called the way the framework calls them: a raw JSON string through the
 * method's tool callback, so a parameter name the model would send wrong fails here.
 *
 * <p>Transactional and rolled back: none of these waits for a commit.
 */
@Transactional
class MemoryToolsIT extends AbstractIntegrationTest {

    @Autowired private Tools tools;
    @Autowired private SecondBrainStore store;
    @Autowired private SecondBrainNotes notes;
    @Autowired private CategoryService categoryService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private HabitService habitService;
    @Autowired private HabitRepository habitRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private User owner;
    private User stranger;

    @BeforeEach
    void seed() {
        owner = newUser();
        stranger = newUser();
    }

    @Test
    @DisplayName("rememberAboutUser writes a profile line marked as the assistant's, once")
    void rememberAboutUserWritesAnAssistantLine() {
        String result = call("rememberAboutUser", "{\"kind\":\"about\",\"text\":\"  Lives with two cats  \"}", owner);
        call("rememberAboutUser", "{\"kind\":\"ABOUT\",\"text\":\"lives with two cats\"}", owner);

        assertThat(result).contains("Saved to the profile").contains("Lives with two cats");
        assertThat(store.facts(owner.getId()))
                .as("the same line twice is one line")
                .extracting(SecondBrainFactDTO::kind, SecondBrainFactDTO::text, SecondBrainFactDTO::origin)
                .containsExactly(tuple(SecondBrainFactKind.ABOUT, "Lives with two cats", SecondBrainFactOrigin.ASSISTANT));
        assertThat(store.facts(stranger.getId())).isEmpty();
    }

    /** The model relays this message, so it has to say where the person makes room. */
    @Test
    @DisplayName("with the profile full, rememberAboutUser fails with a message the assistant can repeat")
    void aFullProfileIsExplainedToTheAssistant() {
        for (int i = 0; i < 20; i++) {
            store.createFact(owner.getId(), SecondBrainFactKind.LIKE, "like " + i);
        }

        assertThatThrownBy(() -> call("rememberAboutUser", "{\"kind\":\"LIKE\",\"text\":\"Cooking\"}", owner))
                .hasMessageContaining("20 lines")
                .hasMessageContaining("Second Brain page (/second-brain)");
        assertThat(store.facts(owner.getId())).hasSize(20);
        assertThatThrownBy(() -> call("rememberAboutUser", "{\"kind\":\"SECRET\",\"text\":\"x\"}", stranger))
                .hasMessageContaining("OBJECTIVE, LIKE or ABOUT");
    }

    @Test
    @DisplayName("rememberAboutItem writes on the person's own thing only, keeping its name")
    void rememberAboutItemChecksTheOwner() {
        UUID gym = habit(owner, "Gym");
        UUID theirs = habit(stranger, "Their gym");

        String result = call("rememberAboutItem",
                "{\"itemType\":\"habit\",\"itemId\":\"" + gym + "\",\"text\":\"Goes with a friend on Mondays\"}", owner);

        assertThat(result).contains("Noted on the Second Brain page").contains("Gym");
        assertThat(notes.readByName(owner.getId(), "gym")).singleElement().satisfies(subject -> {
            assertThat(subject.type()).isEqualTo(SecondBrainSubjectType.HABIT);
            assertThat(subject.notes()).extracting(note -> note.text(), note -> note.origin(), note -> note.subjectName())
                    .containsExactly(tuple("Goes with a friend on Mondays", SecondBrainNoteOrigin.ASSISTANT, "Gym"));
        });

        assertThatThrownBy(() -> call("rememberAboutItem",
                "{\"itemType\":\"HABIT\",\"itemId\":\"" + theirs + "\",\"text\":\"planted\"}", owner))
                .hasMessageContaining("getUserHabits");
        assertThatThrownBy(() -> call("rememberAboutItem",
                "{\"itemType\":\"HABIT\",\"itemId\":\"not-an-id\",\"text\":\"x\"}", owner))
                .hasMessageContaining("do not invent one");
        assertThatThrownBy(() -> call("rememberAboutItem",
                "{\"itemType\":\"LOOSE\",\"itemId\":\"" + gym + "\",\"text\":\"x\"}", owner))
                .hasMessageContaining("HABIT, GOAL, ROUTINE or CATEGORY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM second_brain_notes WHERE user_id = ?",
                Integer.class, stranger.getId())).isZero();
    }

    @Test
    @DisplayName("readItemNotes finds things by part of their name, deleted ones included, and only the caller's")
    void readItemNotesFindsLiveAndDeletedThings() {
        UUID gym = habit(owner, "Gym");
        notes.add(owner.getId(), SecondBrainSubjectType.HABIT, gym, "Rain stops it", SecondBrainNoteOrigin.ANSWER);
        jdbc.update("INSERT INTO second_brain_notes (id, user_id, subject_type, subject_name, former_type, text, origin, "
                + "created_at, updated_at) VALUES (?, ?, 'LOOSE', 'Gym mornings', 'ROUTINE', 'Before the kids wake', "
                + "'ANSWER', now(), now())", UUID.randomUUID(), owner.getId());
        UUID theirs = habit(stranger, "Gym too");
        notes.add(stranger.getId(), SecondBrainSubjectType.HABIT, theirs, "not yours", SecondBrainNoteOrigin.ANSWER);

        String result = call("readItemNotes", "{\"name\":\"GYM\"}", owner);

        assertThat(result).contains("Rain stops it").contains("Before the kids wake").contains("\"deleted\":true")
                .contains("\"type\":\"ROUTINE\"").doesNotContain("not yours");
        assertThat(call("readItemNotes", "{\"name\":\"swimming\"}", owner)).isEqualTo("[]");
    }

    // ---- helpers ----

    private String call(String tool, String json, User caller) {
        ToolCallback callback = Arrays.stream(ToolCallbacks.from(tools))
                .filter(c -> c.getToolDefinition().name().equals(tool))
                .findFirst().orElseThrow();
        return callback.call(json, new ToolContext(Map.of("userId", caller.getId())));
    }

    private UUID habit(User user, String name) {
        categoryService.createCategory(new CategoryRequestDTO(name + " area", "lucide:heart", "", ExperienceLevel.BEGINNER),
                user.getId());
        UUID category = categoryRepository.findAllByUserId(user.getId()).orElseThrow().stream()
                .filter(c -> c.getName().equals(name + " area")).findFirst().orElseThrow().getId();
        habitService.createHabit(new CreateHabitDTO(name, "", "", "lucide:heart", 3, 3, List.of(category),
                ExperienceLevel.BEGINNER), user.getId());
        return habitRepository.findAllByUserId(user.getId()).stream()
                .filter(habit -> habit.getName().equals(name)).findFirst().orElseThrow().getId();
    }

    private User newUser() {
        User account = new User();
        account.setName("memory-tools");
        account.setEmail("memory-tools-" + UUID.randomUUID() + "@test.com");
        account.setPassword("password123");
        account.setGoogleAccount(false);
        account.setTimezone("UTC");
        account.setLanguageInUse("en");
        account.setCompletedDays(new HashSet<>());
        account.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        account.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(account);
    }
}

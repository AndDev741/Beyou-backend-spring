package beyou.beyouapp.backend.integration.notebook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.notebook.ai.NotebookAiService;
import beyou.beyouapp.backend.domain.notebook.ai.NotebookLlm;
import beyou.beyouapp.backend.domain.notebook.ai.draft.NotebookRoadmapDraftRepository;
import beyou.beyouapp.backend.domain.notebook.ai.draft.RoadmapDraftService;
import beyou.beyouapp.backend.domain.notebook.ai.draft.RoadmapDraftStatus;
import beyou.beyouapp.backend.domain.notebook.ai.draft.dto.DraftChoiceDTO;
import beyou.beyouapp.backend.domain.notebook.ai.draft.dto.RoadmapDraftRecordDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.CreateFromDraftRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.DraftNodeInputDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.LlmPayloads;
import beyou.beyouapp.backend.domain.notebook.ai.dto.RoadmapDraftRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.StudyLevel;
import beyou.beyouapp.backend.domain.notebook.dto.PageResponseDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * Roadmap drafts that outlive the dialog, against a real Postgres with the model mocked.
 *
 * <p>Reported in local testing: a click outside the dialog threw away a finished draft, and with
 * it the minute and a half the model took. These tests hold the rules that keep a draft around
 * until the person decides: stored before the model is called, filled in afterwards, never
 * resurrected once deleted, and gone once the topic exists.
 *
 * <p>Not {@code @Transactional}: the model call's result is written in a transaction of its own
 * ({@code RoadmapDraftWrites}), which could not see a row the test's transaction never
 * committed. The job does not start on its own in the test profile, so each test runs it with
 * {@link RoadmapDraftService#run} and asserts on the result. Every test makes its own user.
 */
class NotebookDraftsIT extends AbstractIntegrationTest {

    @MockitoBean private NotebookLlm llm;

    @Autowired private RoadmapDraftService draftService;
    @Autowired private NotebookAiService aiService;
    @Autowired private NotebookRoadmapDraftRepository draftRepository;
    @Autowired private UserRepository userRepository;

    private User user;

    @BeforeEach
    void seed() {
        user = newUser("drafts");
        when(llm.call(eq(LlmPayloads.RoadmapPayload.class), anyString(), any())).thenReturn(
                new LlmPayloads.RoadmapPayload(List.of(
                        new LlmPayloads.RoadmapNode("Fundamentals", "Where it starts.", List.of("Logic"), 10, false),
                        new LlmPayloads.RoadmapNode("Compilers", "Optional.", List.of(), 12, true))));
    }

    /** The draft exists before the model answers, so closing the dialog cannot lose it. */
    @Test
    void aDraftIsStoredAtOnceAndFilledInWhenTheModelAnswers() {
        RoadmapDraftRecordDTO started = draftService.start(user, request("Software Engineering"));

        assertThat(started.status()).isEqualTo(RoadmapDraftStatus.DRAFTING);
        assertThat(started.result()).isNull();
        assertThat(draftService.list(user)).singleElement()
                .satisfies(d -> assertThat(d.status()).isEqualTo(RoadmapDraftStatus.DRAFTING));

        draftService.run(started.id());

        RoadmapDraftRecordDTO ready = draftService.get(user, started.id());
        assertThat(ready.status()).isEqualTo(RoadmapDraftStatus.READY);
        assertThat(ready.result().nodes()).extracting(n -> n.title()).containsExactly("Fundamentals", "Compilers");
        // The form can be refilled from it.
        assertThat(ready.request().title()).isEqualTo("Software Engineering");
        assertThat(ready.request().hoursPerWeek()).isEqualTo(6);
        assertThat(draftService.list(user)).singleElement().satisfies(d -> assertThat(d.nodeCount()).isEqualTo(2));
    }

    @Test
    void aFailedCallLeavesTheDraftFailedWithWhy() {
        when(llm.call(eq(LlmPayloads.RoadmapPayload.class), anyString(), any()))
                .thenThrow(new BusinessException(ErrorKey.AI_UNAVAILABLE, "down"));
        UUID id = draftService.start(user, request("Spanish B1")).id();

        draftService.run(id);

        RoadmapDraftRecordDTO failed = draftService.get(user, id);
        assertThat(failed.status()).isEqualTo(RoadmapDraftStatus.FAILED);
        assertThat(failed.errorKey()).isEqualTo("AI_UNAVAILABLE");
    }

    /** The job only writes to a row that is still DRAFTING; a deleted draft stays deleted. */
    @Test
    void aDraftDeletedWhileTheModelWorksStaysDeleted() {
        UUID id = draftService.start(user, request("Calculus")).id();
        draftService.delete(user, id);

        draftService.run(id);

        assertThat(draftRepository.findById(id)).isEmpty();
    }

    /** One model call per draft; ticks would belong to nodes that are about to be replaced. */
    @Test
    void aDraftBeingWrittenTakesNoRedraftAndNoTicks() {
        UUID id = draftService.start(user, request("Calculus")).id();

        assertThatThrownBy(() -> draftService.redraft(user, id, request("Calculus II")))
                .extracting(e -> ((BusinessException) e).getErrorKey()).isEqualTo(ErrorKey.NOTEBOOK_DRAFT_BUSY);
        assertThatThrownBy(() -> draftService.saveChoices(user, id, List.of(new DraftChoiceDTO(true, false))))
                .extracting(e -> ((BusinessException) e).getErrorKey()).isEqualTo(ErrorKey.NOTEBOOK_DRAFT_BUSY);
    }

    /** "Recover where I was": the ticks come back, and a redraft clears them with the old nodes. */
    @Test
    void ticksAreKeptUntilTheNodesChange() {
        UUID id = draftService.start(user, request("Software Engineering")).id();
        draftService.run(id);

        draftService.saveChoices(user, id, List.of(new DraftChoiceDTO(true, false), new DraftChoiceDTO(true, false)));
        assertThat(draftService.get(user, id).choices()).containsExactly(
                new DraftChoiceDTO(true, false), new DraftChoiceDTO(true, false));
        assertThatThrownBy(() -> draftService.saveChoices(user, id, List.of(new DraftChoiceDTO(true, false))))
                .isInstanceOf(IllegalArgumentException.class);

        draftService.redraft(user, id, request("Software Engineering"));
        // The old nodes stay visible while the new ones are written.
        assertThat(draftService.get(user, id).result().nodes()).hasSize(2);
        draftService.run(id);
        assertThat(draftService.get(user, id).choices()).isNull();
    }

    /** A restart ends every call in flight; the draft says so instead of drafting forever. */
    @Test
    void aRestartFailsTheDraftsItInterrupted() {
        UUID id = draftService.start(user, request("Networks")).id();

        draftService.failInterrupted();

        RoadmapDraftRecordDTO failed = draftService.get(user, id);
        assertThat(failed.status()).isEqualTo(RoadmapDraftStatus.FAILED);
        assertThat(failed.errorKey()).isEqualTo("NOTEBOOK_DRAFT_INTERRUPTED");
    }

    @Test
    void somebodyElsesDraftIsNotOwned() {
        UUID id = draftService.start(user, request("Private plans")).id();
        User stranger = newUser("drafts-stranger");

        assertThatThrownBy(() -> draftService.get(stranger, id))
                .extracting(e -> ((BusinessException) e).getErrorKey()).isEqualTo(ErrorKey.NOTEBOOK_DRAFT_NOT_OWNED);
        assertThatThrownBy(() -> draftService.delete(stranger, id))
                .extracting(e -> ((BusinessException) e).getErrorKey()).isEqualTo(ErrorKey.NOTEBOOK_DRAFT_NOT_OWNED);
        assertThat(draftService.list(stranger)).isEmpty();
    }

    /** Creating the topic ends the draft, and naming somebody else's draft deletes nothing. */
    @Test
    void creatingTheTopicDeletesItsDraftAndOnlyItsOwn() {
        UUID mine = draftService.start(user, request("Software Engineering")).id();
        draftService.run(mine);
        User stranger = newUser("drafts-other");
        UUID theirs = draftService.start(stranger, request("Theirs")).id();

        PageResponseDTO topic = aiService.createFromDraft(user, create("Software Engineering", mine));
        aiService.createFromDraft(user, create("Second", theirs));

        assertThat(topic.title()).isEqualTo("Software Engineering");
        assertThat(draftRepository.findById(mine)).isEmpty();
        assertThat(draftRepository.findById(theirs)).isPresent();
    }

    @Test
    void draftsStopAtTheLimit() {
        for (int i = 0; i < RoadmapDraftService.MAX_DRAFTS; i++) {
            draftService.start(user, request("Topic " + i));
        }

        assertThatThrownBy(() -> draftService.start(user, request("One too many")))
                .extracting(e -> ((BusinessException) e).getErrorKey()).isEqualTo(ErrorKey.NOTEBOOK_DRAFT_LIMIT_REACHED);
    }

    private static RoadmapDraftRequestDTO request(String title) {
        return new RoadmapDraftRequestDTO(title, "Interviews", StudyLevel.SOME, 6, null, null, null, null);
    }

    private static CreateFromDraftRequestDTO create(String title, UUID draftId) {
        return new CreateFromDraftRequestDTO(title, null, null, null, null, null,
                List.of(new DraftNodeInputDTO("Fundamentals", "Where it starts.", List.of(), 10, null)), draftId);
    }

    private User newUser(String tag) {
        User u = new User();
        u.setName(tag);
        u.setEmail(tag + "-" + UUID.randomUUID() + "@test.com");
        u.setPassword("password123");
        u.setGoogleAccount(false);
        u.setTimezone("UTC");
        u.setCompletedDays(new HashSet<>());
        u.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        u.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(u);
    }
}

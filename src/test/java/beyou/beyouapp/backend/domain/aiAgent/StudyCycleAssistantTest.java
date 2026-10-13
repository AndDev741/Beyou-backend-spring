package beyou.beyouapp.backend.domain.aiAgent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import beyou.beyouapp.backend.domain.aiAgent.notebook.StudyCycleAssistant;
import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookPageKind;
import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.cycle.BlockMode;
import beyou.beyouapp.backend.domain.notebook.cycle.GoalSource;
import beyou.beyouapp.backend.domain.notebook.cycle.LapBlockState;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyCycleService;
import beyou.beyouapp.backend.domain.notebook.cycle.StudySession;
import beyou.beyouapp.backend.domain.notebook.cycle.StudySessionRepository;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.BlockPatchDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.BlockProgressDTO;
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
import beyou.beyouapp.backend.domain.notebook.cycle.dto.SubjectRefDTO;
import beyou.beyouapp.backend.user.User;

/**
 * The assistant's study cycle tools turn names into ids and loose values into the DTOs the
 * screens send, and refuse rather than guess when a name is unknown or ambiguous.
 */
@ExtendWith(MockitoExtension.class)
class StudyCycleAssistantTest {

    @Mock private StudyCycleService cycleService;
    @Mock private NotebookPageRepository pageRepository;
    @Mock private StudySessionRepository sessionRepository;
    @InjectMocks private StudyCycleAssistant assistant;

    private final User user = new User();
    private NotebookPage portugues;

    @BeforeEach
    void setUp() {
        user.setId(UUID.randomUUID());
        user.setTimezone("UTC");
        portugues = topic("Português");
    }

    /** Weekdays in Portuguese, "7:00" for 07:00, days left out rest, minutes snap to 5. */
    @Test
    void aCycleIsBuiltFromLooseDaysAndTopicTitles() {
        when(pageRepository.findByUserIdAndTitleIgnoreCase(user.getId(), "português")).thenReturn(List.of(portugues));
        when(cycleService.create(eq(user), any())).thenReturn(new StudyCycleDTO(UUID.randomUUID(), "Concurso",
                BlockMode.GOAL, 90, 1, List.of(), List.of(), new LapSummaryDTO(1, null, null, 0, 90, 0, 1, 0, 0),
                List.of(), List.of()));

        assistant.create(user, "Concurso",
                List.of(new StudyCycleAssistant.DayInput("segunda-feira", 182, "7:00"),
                        new StudyCycleAssistant.DayInput("SATURDAY", 240, null)),
                List.of(new StudyCycleAssistant.TopicInput("português", 5, null)), null, null);

        ArgumentCaptor<CycleConfigRequestDTO> captor = ArgumentCaptor.forClass(CycleConfigRequestDTO.class);
        verify(cycleService).create(eq(user), captor.capture());
        CycleConfigRequestDTO request = captor.getValue();
        assertThat(request.days()).hasSize(7);
        assertThat(request.days()).filteredOn(d -> d.weekday() == DayOfWeek.MONDAY).singleElement()
                .isEqualTo(new CycleDayDTO(DayOfWeek.MONDAY, 180, "07:00"));
        assertThat(request.days()).filteredOn(d -> d.weekday() == DayOfWeek.SUNDAY).singleElement()
                .satisfies(d -> assertThat(d.minutes()).isZero());
        assertThat(request.topics()).singleElement().satisfies(t -> {
            assertThat(t.topicId()).isEqualTo(portugues.getId());
            assertThat(t.importance()).isEqualTo(5);
            assertThat(t.mastery()).as("left out reads as the middle").isEqualTo(3);
        });
        assertThat(request.defaultMode()).isEqualTo(BlockMode.GOAL);
        assertThat(request.maxBlockMinutes()).isEqualTo(90);
    }

    @Test
    void anUnknownTopicListsTheOnesThereAre() {
        when(pageRepository.findByUserIdAndTitleIgnoreCase(user.getId(), "Inglês")).thenReturn(List.of());
        when(pageRepository.findByUserIdAndKindOrderByCreatedAtAsc(user.getId(), NotebookPageKind.TOPIC))
                .thenReturn(List.of(portugues));

        assertThatThrownBy(() -> assistant.logSession(user, "Inglês", 30, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No notebook page is titled \"Inglês\"")
                .hasMessageContaining("\"Português\"");
        verify(cycleService, never()).logSession(any(), any());
    }

    @Test
    void twoTopicsWithOneTitleAreRefusedNotGuessed() {
        NotebookPage twin = topic("Português");
        when(pageRepository.findByUserIdAndTitleIgnoreCase(user.getId(), "Português")).thenReturn(List.of(portugues, twin));
        when(pageRepository.findByIdIn(any())).thenReturn(List.of(portugues, twin));

        assertThatThrownBy(() -> assistant.logSession(user, "Português", 30, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Several pages are titled")
                .hasMessageContaining(twin.getId().toString());
    }

    /**
     * A subject is any page, so the title is looked up across every page, and an area inside a
     * topic is found by its own title.
     */
    @Test
    void aSubjectInsideATopicIsFoundByItsTitle() {
        NotebookPage engineer = topic("Software Engineer");
        NotebookPage implementacao = pageIn(engineer, "Implementação 2.0");
        when(pageRepository.findByUserIdAndTitleIgnoreCase(user.getId(), "implementação 2.0"))
                .thenReturn(List.of(implementacao));
        when(cycleService.create(eq(user), any())).thenReturn(new StudyCycleDTO(UUID.randomUUID(), "Senior",
                BlockMode.GOAL, 90, 1, List.of(), List.of(), new LapSummaryDTO(1, null, null, 0, 90, 0, 1, 0, 0),
                List.of(), List.of()));

        assistant.create(user, "Senior", List.of(new StudyCycleAssistant.DayInput("monday", 120, null)),
                List.of(new StudyCycleAssistant.TopicInput("implementação 2.0", 4, 2)), null, null);

        ArgumentCaptor<CycleConfigRequestDTO> captor = ArgumentCaptor.forClass(CycleConfigRequestDTO.class);
        verify(cycleService).create(eq(user), captor.capture());
        assertThat(captor.getValue().topics()).singleElement()
                .satisfies(t -> assertThat(t.topicId()).isEqualTo(implementacao.getId()));
    }

    /**
     * Two areas with one name in two topics is the usual case once subjects are pages. The
     * refusal names each with the topic it lives in, so the model can ask "which Fundamentos?".
     */
    @Test
    void aTitleOnPagesOfTwoTopicsIsRefusedNamingEachTopic() {
        NotebookPage engineer = topic("Software Engineer");
        NotebookPage espanhol = topic("Espanhol C1");
        NotebookPage first = pageIn(engineer, "Fundamentos");
        NotebookPage second = pageIn(espanhol, "Fundamentos");
        when(pageRepository.findByUserIdAndTitleIgnoreCase(user.getId(), "Fundamentos")).thenReturn(List.of(first, second));
        when(pageRepository.findByIdIn(any())).thenReturn(List.of(engineer, espanhol));

        assertThatThrownBy(() -> assistant.logSession(user, "Fundamentos", 30, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Several pages are titled \"Fundamentos\"")
                .hasMessageContaining("in \"Software Engineer\", id " + first.getId())
                .hasMessageContaining("in \"Espanhol C1\", id " + second.getId());
        verify(cycleService, never()).logSession(any(), any());
    }

    @Test
    void aSessionByIdGoesToTheCycleAndSaysWhere() {
        portugues.setUser(user);
        when(pageRepository.findById(portugues.getId())).thenReturn(Optional.of(portugues));
        when(cycleService.logSession(eq(user), any())).thenReturn(new SessionResponseDTO(UUID.randomUUID(),
                new SessionResponseDTO.Credited(UUID.randomUUID(), "Concurso", UUID.randomUUID(), 9, 0, 45, 75,
                        LapBlockState.OPEN)));

        var result = assistant.logSession(user, "open " + portugues.getId(), 45, "livro", "2026-10-10");

        ArgumentCaptor<SessionRequestDTO> captor = ArgumentCaptor.forClass(SessionRequestDTO.class);
        verify(cycleService).logSession(eq(user), captor.capture());
        assertThat(captor.getValue().topicId()).isEqualTo(portugues.getId());
        assertThat(captor.getValue().studiedOn()).hasToString("2026-10-10");
        assertThat((String) result.get("success")).contains("block 9 went from 0 to 45 of 75");
    }

    // ------------------------------------------------------- edit, archive, delete

    /**
     * An edit sends the whole configuration, so everything the model left out has to come from the
     * cycle as it is: the name, the other days with their start times, the subjects with their
     * weights and their minutes set by hand.
     */
    @Test
    void anEditKeepsEverythingItDoesNotName() {
        UUID id = UUID.randomUUID();
        StudyCycleDTO concurso = cycle(id, "Concurso", List.of(
                new CycleDayDTO(DayOfWeek.MONDAY, 180, "19:00"), new CycleDayDTO(DayOfWeek.SATURDAY, 240, "08:00")),
                List.of(new CycleTopicViewDTO(portugues.getId(), 5, 2, 60, ref(portugues))), List.of());
        when(cycleService.list(user)).thenReturn(List.of(summary(id, "Concurso")));
        when(cycleService.get(user, id)).thenReturn(concurso);
        when(cycleService.update(eq(user), eq(id), any())).thenReturn(concurso);

        assistant.edit(user, "concurso",
                null, List.of(new StudyCycleAssistant.DayInput("sábado", 0, null),
                        new StudyCycleAssistant.DayInput("monday", null, "20:00")),
                null, null, null);

        ArgumentCaptor<CycleConfigRequestDTO> captor = ArgumentCaptor.forClass(CycleConfigRequestDTO.class);
        verify(cycleService).update(eq(user), eq(id), captor.capture());
        CycleConfigRequestDTO request = captor.getValue();
        assertThat(request.name()).isEqualTo("Concurso");
        assertThat(request.defaultMode()).isEqualTo(BlockMode.GOAL);
        assertThat(request.maxBlockMinutes()).isEqualTo(90);
        assertThat(request.days()).hasSize(7)
                .contains(new CycleDayDTO(DayOfWeek.MONDAY, 180, "20:00"),
                        new CycleDayDTO(DayOfWeek.SATURDAY, 0, "08:00"),
                        new CycleDayDTO(DayOfWeek.SUNDAY, 0, null));
        assertThat(request.topics()).containsExactly(new CycleTopicDTO(portugues.getId(), 5, 2, 60));
    }

    /** New subjects list: one that stays keeps its weights unless new ones come, a new one starts at 3. */
    @Test
    void anEditedSubjectListKeepsTheWeightsOfTheSubjectsThatStay() {
        UUID id = UUID.randomUUID();
        NotebookPage direito = topic("Direito");
        StudyCycleDTO concurso = cycle(id, "Concurso", week(),
                List.of(new CycleTopicViewDTO(portugues.getId(), 5, 2, 60, ref(portugues))), List.of());
        when(cycleService.list(user)).thenReturn(List.of(summary(id, "Concurso")));
        when(cycleService.get(user, id)).thenReturn(concurso);
        when(pageRepository.findByUserIdAndTitleIgnoreCase(user.getId(), "Direito")).thenReturn(List.of(direito));
        when(pageRepository.findByUserIdAndTitleIgnoreCase(user.getId(), "Português")).thenReturn(List.of(portugues));
        when(cycleService.update(eq(user), eq(id), any())).thenReturn(concurso);

        assistant.edit(user, "Concurso", null, null,
                List.of(new StudyCycleAssistant.TopicInput("Direito", null, 1),
                        new StudyCycleAssistant.TopicInput("Português", null, 4)),
                null, null);

        ArgumentCaptor<CycleConfigRequestDTO> captor = ArgumentCaptor.forClass(CycleConfigRequestDTO.class);
        verify(cycleService).update(eq(user), eq(id), captor.capture());
        assertThat(captor.getValue().topics()).containsExactly(
                new CycleTopicDTO(direito.getId(), 3, 1, null),
                new CycleTopicDTO(portugues.getId(), 5, 4, 60));
        assertThat(captor.getValue().days()).isEqualTo(week());
    }

    @Test
    void aBlockIsPatchedByItsNumberWithOnlyWhatWasSaid() {
        UUID id = UUID.randomUUID();
        StudyBlockDTO third = block(3, portugues);
        StudyCycleDTO concurso = cycle(id, "Concurso", week(), List.of(), List.of(block(1, portugues), third));
        when(cycleService.list(user)).thenReturn(List.of(summary(id, "Concurso")));
        when(cycleService.get(user, id)).thenReturn(concurso);
        when(cycleService.patchBlock(eq(user), eq(id), eq(third.id()), any())).thenReturn(concurso);

        assistant.editBlock(user, "Concurso", 3, null, 50, "next", null, "");

        ArgumentCaptor<BlockPatchDTO> captor = ArgumentCaptor.forClass(BlockPatchDTO.class);
        verify(cycleService).patchBlock(eq(user), eq(id), eq(third.id()), captor.capture());
        BlockPatchDTO patch = captor.getValue();
        assertThat(patch.getMode()).as("a concept makes it a GOAL block").isEqualTo(BlockMode.GOAL);
        assertThat(patch.getGoalSource()).isEqualTo(GoalSource.NEXT);
        assertThat(patch.getPlannedMinutes()).isEqualTo(50);
        assertThat(patch.has("note")).as("an empty note clears it").isTrue();
        assertThat(patch.has("requireQuiz")).as("left out stays as it is").isFalse();
        assertThat(patch.has("pageId")).isFalse();
    }

    @Test
    void aBlockEditThatContradictsItselfOrNamesNothingIsRefused() {
        UUID id = UUID.randomUUID();
        StudyCycleDTO concurso = cycle(id, "Concurso", week(), List.of(), List.of(block(1, portugues)));
        when(cycleService.list(user)).thenReturn(List.of(summary(id, "Concurso")));
        when(cycleService.get(user, id)).thenReturn(concurso);

        assertThatThrownBy(() -> assistant.editBlock(user, "Concurso", 1, "TIME", null, "Crase", null, null))
                .hasMessageContaining("A TIME block has no concept");
        assertThatThrownBy(() -> assistant.editBlock(user, "Concurso", 1, null, null, null, null, null))
                .hasMessageContaining("Say what to change");
        assertThatThrownBy(() -> assistant.editBlock(user, "Concurso", 7, null, 30, null, null, null))
                .hasMessageContaining("has blocks 1 to 1");
        verify(cycleService, never()).patchBlock(any(), any(), any(), any());
    }

    @Test
    void aCycleIsDeletedByItsNameInAnyCase() {
        UUID id = UUID.randomUUID();
        when(cycleService.list(user)).thenReturn(List.of(summary(id, "Ciclo engenheiro senior")));

        var result = assistant.delete(user, "CICLO ENGENHEIRO SENIOR");

        verify(cycleService).delete(user, id);
        verify(cycleService, never()).archive(any(), any());
        assertThat((String) result.get("success")).contains("Deleted \"Ciclo engenheiro senior\"");
    }

    /**
     * Only the caller's active cycles answer. An id of somebody else's cycle, or of an archived one,
     * gets the refusal an unknown name gets, and nothing is deleted.
     */
    @Test
    void aCycleThatIsNotOneOfTheCallersActiveOnesIsNeverDeleted() {
        when(cycleService.list(user)).thenReturn(List.of(summary(UUID.randomUUID(), "Concurso")));
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> assistant.delete(user, stranger.toString()))
                .hasMessageContaining("No active study cycle has the id " + stranger)
                .hasMessageContaining("\"Concurso\"");
        assertThatThrownBy(() -> assistant.archive(user, "Faculdade"))
                .hasMessageContaining("No active study cycle is named \"Faculdade\"");
        verify(cycleService, never()).delete(any(), any());
        verify(cycleService, never()).archive(any(), any());
    }

    // ------------------------------------------------------------------ sessions

    @Test
    void sessionsAreListedWithTheirIdsAndWhereTheMinutesWent() {
        NotebookPage engineer = topic("Software Engineer");
        NotebookPage implementacao = pageIn(engineer, "Implementação 2.0");
        UUID cycleId = UUID.randomUUID();
        StudySession session = session(user, implementacao.getId(), 45);
        session.setCycleId(cycleId);
        when(sessionRepository.findRange(eq(user.getId()), any(), any())).thenReturn(List.of(session));
        when(pageRepository.findByIdIn(any())).thenReturn(List.of(implementacao)).thenReturn(List.of(engineer));
        when(cycleService.list(user)).thenReturn(List.of(summary(cycleId, "Senior")));

        var result = assistant.sessions(user, null);

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(sessionRepository).findRange(eq(user.getId()), from.capture(), to.capture());
        assertThat(from.getValue()).as("seven days, today included").isEqualTo(to.getValue().minusDays(6));
        assertThat(result.get("sessions")).asList().singleElement().satisfies(row -> assertThat(row)
                .hasFieldOrPropertyWithValue("id", session.getId())
                .hasFieldOrPropertyWithValue("subject", "Software Engineer › Implementação 2.0")
                .hasFieldOrPropertyWithValue("minutes", 45)
                .hasFieldOrPropertyWithValue("cycle", "Senior"));
        assertThatThrownBy(() -> assistant.sessions(user, 40)).hasMessageContaining("days is 1 to 31");
    }

    @Test
    void onlyTheCallersOwnSessionIsDeleted() {
        User stranger = new User();
        stranger.setId(UUID.randomUUID());
        StudySession theirs = session(stranger, portugues.getId(), 30);
        StudySession mine = session(user, portugues.getId(), 45);
        when(sessionRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));
        when(sessionRepository.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(pageRepository.findByIdIn(any())).thenReturn(List.of(portugues));

        assertThatThrownBy(() -> assistant.deleteSession(user, theirs.getId().toString()))
                .hasMessageContaining("No session logged by this user has the id");
        verify(cycleService, never()).deleteSession(any(), any());

        var result = assistant.deleteSession(user, mine.getId().toString());

        verify(cycleService).deleteSession(user, mine.getId());
        assertThat((String) result.get("success")).contains("45 minutes of \"Português\"");
    }

    private static StudyCycleSummaryDTO summary(UUID id, String name) {
        return new StudyCycleSummaryDTO(id, name, 1, new LapSummaryDTO(1, null, null, 0, 90, 0, 1, 0, 0),
                new StudyCycleSummaryDTO.Today(0, null));
    }

    private static StudyCycleDTO cycle(UUID id, String name, List<CycleDayDTO> days, List<CycleTopicViewDTO> topics,
            List<StudyBlockDTO> blocks) {
        return new StudyCycleDTO(id, name, BlockMode.GOAL, 90, 1, days, topics,
                new LapSummaryDTO(1, null, null, 0, 90, 0, blocks.size(), 0, 0), blocks, List.of());
    }

    private static List<CycleDayDTO> week() {
        return java.util.Arrays.stream(DayOfWeek.values()).map(d -> new CycleDayDTO(d, 60, null)).toList();
    }

    private static SubjectRefDTO ref(NotebookPage page) {
        return new SubjectRefDTO(page.getId(), page.getTitle(), null, null);
    }

    private static StudyBlockDTO block(int position, NotebookPage subject) {
        return new StudyBlockDTO(UUID.randomUUID(), position, ref(subject), BlockMode.TIME, 45, null, null, false, null,
                new BlockProgressDTO(1, 0, LapBlockState.OPEN, null, null, false, null));
    }

    private static StudySession session(User owner, UUID topicId, int minutes) {
        StudySession session = new StudySession();
        session.setId(UUID.randomUUID());
        session.setUser(owner);
        session.setTopicId(topicId);
        session.setMinutes(minutes);
        session.setStudiedOn(LocalDate.of(2026, 10, 12));
        return session;
    }

    private NotebookPage pageIn(NotebookPage topic, String title) {
        NotebookPage page = new NotebookPage();
        page.setId(UUID.randomUUID());
        page.setUser(user);
        page.setKind(NotebookPageKind.PAGE);
        page.setParentId(topic.getId());
        page.setTopicId(topic.getId());
        page.setTitle(title);
        return page;
    }

    private static NotebookPage topic(String title) {
        NotebookPage page = new NotebookPage();
        page.setId(UUID.randomUUID());
        page.setKind(NotebookPageKind.TOPIC);
        page.setTitle(title);
        return page;
    }
}

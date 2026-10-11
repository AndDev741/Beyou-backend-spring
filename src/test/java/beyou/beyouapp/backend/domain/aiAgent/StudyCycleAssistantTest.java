package beyou.beyouapp.backend.domain.aiAgent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.util.List;
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
import beyou.beyouapp.backend.domain.notebook.cycle.LapBlockState;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyCycleService;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleConfigRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleDayDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.LapSummaryDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.SessionRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.SessionResponseDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyCycleDTO;
import beyou.beyouapp.backend.user.User;

/**
 * The assistant's study cycle tools turn names into ids and loose values into the DTOs the
 * screens send, and refuse rather than guess when a name is unknown or ambiguous.
 */
@ExtendWith(MockitoExtension.class)
class StudyCycleAssistantTest {

    @Mock private StudyCycleService cycleService;
    @Mock private NotebookPageRepository pageRepository;
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
        when(pageRepository.findByUserIdAndKindOrderByCreatedAtAsc(user.getId(), NotebookPageKind.TOPIC))
                .thenReturn(List.of(portugues, topic("Matemática")));
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
        when(pageRepository.findByUserIdAndKindOrderByCreatedAtAsc(user.getId(), NotebookPageKind.TOPIC))
                .thenReturn(List.of(portugues));

        assertThatThrownBy(() -> assistant.logSession(user, "Inglês", 30, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No notebook topic is titled \"Inglês\"")
                .hasMessageContaining("\"Português\"");
        verify(cycleService, never()).logSession(any(), any());
    }

    @Test
    void twoTopicsWithOneTitleAreRefusedNotGuessed() {
        NotebookPage twin = topic("Português");
        when(pageRepository.findByUserIdAndKindOrderByCreatedAtAsc(user.getId(), NotebookPageKind.TOPIC))
                .thenReturn(List.of(portugues, twin));

        assertThatThrownBy(() -> assistant.logSession(user, "Português", 30, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Several topics are titled")
                .hasMessageContaining(twin.getId().toString());
    }

    @Test
    void aSessionByIdGoesToTheCycleAndSaysWhere() {
        when(pageRepository.findByUserIdAndKindOrderByCreatedAtAsc(user.getId(), NotebookPageKind.TOPIC))
                .thenReturn(List.of(portugues));
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

    private static NotebookPage topic(String title) {
        NotebookPage page = new NotebookPage();
        page.setId(UUID.randomUUID());
        page.setKind(NotebookPageKind.TOPIC);
        page.setTitle(title);
        return page;
    }
}

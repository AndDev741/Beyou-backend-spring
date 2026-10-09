package beyou.beyouapp.backend.integration.notebook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.ai.NotebookLlm;
import beyou.beyouapp.backend.domain.notebook.ai.dto.LlmPayloads;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardService;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateNodeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreateTopicRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.UpdateContentRequestDTO;
import beyou.beyouapp.backend.domain.notebook.source.LinkFetcher;
import beyou.beyouapp.backend.domain.notebook.source.NotebookSourceService;
import beyou.beyouapp.backend.domain.notebook.source.discovery.DiscoveryProperties;
import beyou.beyouapp.backend.domain.notebook.source.discovery.SearchHit;
import beyou.beyouapp.backend.domain.notebook.source.discovery.SourceDiscoveryService;
import beyou.beyouapp.backend.domain.notebook.source.discovery.WebSearchClient;
import beyou.beyouapp.backend.domain.notebook.source.discovery.dto.DiscoveryResultDTO;
import beyou.beyouapp.backend.domain.notebook.source.dto.AddLinkRequestDTO;
import beyou.beyouapp.backend.domain.notebook.study.NotebookStudyService;
import beyou.beyouapp.backend.domain.notebook.study.StudyScope;
import beyou.beyouapp.backend.domain.notebook.study.dto.StudyResponseDTO;
import beyou.beyouapp.backend.domain.notebook.study.dto.StudySetupRequestDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * The study room's setup and "find sources for me", with the model, the web search and the link
 * fetcher mocked. What is under test is what reaches the model (which notes, the goal) and what
 * the person is offered (only pages that open and are not already sources).
 */
@Transactional
class NotebookStudySetupIT extends AbstractIntegrationTest {

    @MockitoBean private NotebookLlm llm;
    @MockitoBean private WebSearchClient search;
    @MockitoBean private LinkFetcher fetcher;

    @Autowired private NotebookStudyService studyService;
    @Autowired private SourceDiscoveryService discoveryService;
    @Autowired private NotebookSourceService sourceService;
    @Autowired private NotebookPageService pageService;
    @Autowired private NotebookBoardService boardService;
    @Autowired private UserRepository userRepository;

    private User user;
    private UUID topic;
    private UUID algorithms;
    private UUID sorting;

    @BeforeEach
    void seed() {
        user = newUser("study-setup");
        topic = pageService.createTopic(user, new CreateTopicRequestDTO("Computer Science", null, null, null, null, null)).id();
        algorithms = node(topic, "Algorithms");
        sorting = node(algorithms, "Sorting");
        UUID networks = node(topic, "Networks");
        notes(algorithms, "Big O describes how cost grows with the input.");
        notes(sorting, "Merge sort splits the list in half and merges the sorted halves.");
        notes(networks, "TCP retransmits lost segments.");
        when(llm.call(eq(LlmPayloads.AnswerPayload.class), anyString(), any()))
                .thenReturn(new LlmPayloads.AnswerPayload("An answer [1].", List.of(1)));
        when(fetcher.validate(anyString())).thenAnswer(inv -> URI.create(inv.getArgument(0)));
    }

    /** The room opens on its setup until it is saved, and each scope says what it would read. */
    @Test
    void theSetupIsSavedAndEachScopeSaysWhatItReads() {
        StudyResponseDTO before = studyService.study(user, algorithms);
        assertThat(before.setup().configuredAt()).isNull();
        assertThat(before.setup().scope()).isEqualTo(StudyScope.PAGE);
        assertThat(before.scopes()).extracting(o -> o.scope() + ":" + o.pages())
                .containsExactly("PAGE:1", "SUBTREE:2", "TOPIC:3");

        studyService.saveSetup(user, algorithms, new StudySetupRequestDTO("  Pass the algorithms exam  ", StudyScope.SUBTREE));

        StudyResponseDTO after = studyService.study(user, algorithms);
        assertThat(after.setup().configuredAt()).isNotNull();
        assertThat(after.setup().goal()).isEqualTo("Pass the algorithms exam");
        assertThat(after.setup().scope()).isEqualTo(StudyScope.SUBTREE);
    }

    /** PAGE reads the page and the pages above it; SUBTREE adds the pages under it. */
    @Test
    void answersReadTheNotesTheScopeCovers() {
        studyService.chat(user, algorithms, "how does merge sort work");
        assertThat(lastPrompt()).doesNotContain("Merge sort splits");

        studyService.saveSetup(user, algorithms, new StudySetupRequestDTO(null, StudyScope.SUBTREE));
        studyService.chat(user, algorithms, "how does merge sort work");
        assertThat(lastPrompt()).contains("Merge sort splits").doesNotContain("TCP retransmits");

        studyService.saveSetup(user, algorithms, new StudySetupRequestDTO(null, StudyScope.TOPIC));
        studyService.chat(user, algorithms, "how does tcp handle loss");
        assertThat(lastPrompt()).contains("TCP retransmits");
    }

    /** The goal steers every answer on the page; a blank one clears it. */
    @Test
    void theGoalGoesWithEveryAnswer() {
        studyService.saveSetup(user, algorithms, new StudySetupRequestDTO("Pass the algorithms exam", StudyScope.PAGE));
        studyService.chat(user, algorithms, "what is big o");
        assertThat(lastPrompt()).contains("The person's goal for studying this: Pass the algorithms exam");

        studyService.saveSetup(user, algorithms, new StudySetupRequestDTO("   ", StudyScope.PAGE));
        studyService.chat(user, algorithms, "what is big o");
        assertThat(lastPrompt()).doesNotContain("goal for studying");
        assertThat(studyService.study(user, algorithms).setup().goal()).isNull();
    }

    /**
     * Only pages that open are offered, at the address they land on, once each, and never one the
     * page already has as a source.
     */
    @Test
    void discoveryOffersOnlyPagesThatOpenAndAreNotSourcesYet() {
        sourceService.addLink(user, algorithms, new AddLinkRequestDTO("https://already.example/notes"));
        when(search.provider()).thenReturn(DiscoveryProperties.Provider.GEMINI);
        when(search.search(anyString(), anyString())).thenReturn(List.of(
                new SearchHit("mit.edu", "https://redirect.example/1", "MIT's notes cover merge sort with proofs."),
                new SearchHit("dead.example", "https://dead.example/", "Gone."),
                new SearchHit("already", "https://already.example/notes", "Already a source."),
                new SearchHit("mit.edu again", "https://redirect.example/2", "The same page through another link.")));
        when(fetcher.resolve("https://redirect.example/1"))
                .thenReturn(new LinkFetcher.Resolved(URI.create("https://ocw.mit.edu/sorting/"), "Sorting | MIT OCW"));
        when(fetcher.resolve("https://redirect.example/2"))
                .thenReturn(new LinkFetcher.Resolved(URI.create("https://ocw.mit.edu/sorting"), null));
        when(fetcher.resolve("https://dead.example/"))
                .thenThrow(new BusinessException(ErrorKey.NOTEBOOK_SOURCE_FETCH_FAILED, "404"));
        when(fetcher.resolve("https://already.example/notes"))
                .thenReturn(new LinkFetcher.Resolved(URI.create("https://already.example/notes/"), "Notes"));

        DiscoveryResultDTO result = discoveryService.discover(user, algorithms, "sorting algorithms with proofs");

        assertThat(result.provider()).isEqualTo("GEMINI");
        assertThat(result.sources()).singleElement().satisfies(source -> {
            assertThat(source.url()).isEqualTo("https://ocw.mit.edu/sorting/");
            assertThat(source.title()).isEqualTo("Sorting | MIT OCW");
            assertThat(source.domain()).isEqualTo("ocw.mit.edu");
            assertThat(source.summary()).isEqualTo("MIT's notes cover merge sort with proofs.");
        });
        assertThat(result.skipped()).isEqualTo(3);
        assertThat(studyService.study(user, algorithms).discovery()).isTrue();
    }

    @Test
    void withoutASearchDiscoveryIsOff() {
        when(search.provider()).thenReturn(null);

        assertThat(studyService.study(user, algorithms).discovery()).isFalse();
        assertThatThrownBy(() -> discoveryService.discover(user, algorithms, "sorting"))
                .extracting(e -> ((BusinessException) e).getErrorKey()).isEqualTo(ErrorKey.NOTEBOOK_DISCOVERY_UNAVAILABLE);
    }

    private String lastPrompt() {
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm, org.mockito.Mockito.atLeastOnce()).call(eq(LlmPayloads.AnswerPayload.class), prompt.capture(), any());
        return prompt.getValue();
    }

    private UUID node(UUID boardPageId, String title) {
        return boardService.addNode(user, boardPageId,
                new CreateNodeRequestDTO(null, title, null, null, 0.0, 0.0, null, null)).node().pageId();
    }

    private void notes(UUID page, String text) {
        pageService.saveContent(user, page, new UpdateContentRequestDTO(
                "[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\",\"styles\":{}}]}]", null));
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

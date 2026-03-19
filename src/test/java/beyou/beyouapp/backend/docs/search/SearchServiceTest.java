package beyou.beyouapp.backend.docs.search;

import beyou.beyouapp.backend.docs.api.ApiControllerTopicRepository;
import beyou.beyouapp.backend.docs.architecture.ArchitectureTopicRepository;
import beyou.beyouapp.backend.docs.blog.BlogTopicRepository;
import beyou.beyouapp.backend.docs.project.ProjectTopicRepository;
import beyou.beyouapp.backend.docs.search.dto.SearchRequestDTO;
import beyou.beyouapp.backend.docs.search.dto.SearchResultDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private ArchitectureTopicRepository architectureRepository;
    @Mock
    private BlogTopicRepository blogRepository;
    @Mock
    private ApiControllerTopicRepository apiRepository;
    @Mock
    private ProjectTopicRepository projectRepository;

    @InjectMocks
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        // No-op for now
    }

    @Test
    void search_withShortQuery_throwsIllegalArgumentException() {
        SearchRequestDTO request = new SearchRequestDTO("a", "en", "all", 10, 0);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> searchService.search(request));
        assertEquals("Search query must be at least 2 characters", exception.getMessage());
    }

    @Test
    void search_withValidQuery_returnsEmptyListWhenNoResults() {
        // Given
        SearchRequestDTO request = new SearchRequestDTO("test", "en", "all", 10, 0);
        when(architectureRepository.searchByLocaleAndQuery(any(), any(), any())).thenReturn(List.of());
        when(blogRepository.searchByLocaleAndQuery(any(), any(), any())).thenReturn(List.of());
        when(apiRepository.searchByLocaleAndQuery(any(), any(), any())).thenReturn(List.of());
        when(projectRepository.searchByLocaleAndQuery(any(), any(), any())).thenReturn(List.of());

        // When
        List<SearchResultDTO> results = searchService.search(request);

        // Then
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    // Additional tests could be added for each category, highlighting, scoring, etc.
}
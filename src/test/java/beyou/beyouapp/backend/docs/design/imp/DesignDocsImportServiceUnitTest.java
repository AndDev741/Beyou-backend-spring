package beyou.beyouapp.backend.docs.design.imp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import beyou.beyouapp.backend.docs.design.DesignTopicRepository;
import beyou.beyouapp.backend.docs.design.dto.DesignDocsImportResultDTO;
import beyou.beyouapp.backend.docs.design.entity.DesignTopic;
import beyou.beyouapp.backend.docs.design.entity.DesignTopicStatus;
import beyou.beyouapp.backend.docs.design.imp.DesignDocsImportService.DesignDocsImportContent;
import beyou.beyouapp.backend.docs.design.imp.DesignDocsImportService.DesignDocsImportTopic;

@ExtendWith(MockitoExtension.class)
public class DesignDocsImportServiceUnitTest {

    @Mock
    private DesignTopicRepository topicRepository;

    @Mock
    private DesignDocsImportParser parser;

    private DesignDocsImportService importService;

    @BeforeEach
    void setup() {
        importService = new DesignDocsImportService(topicRepository, parser);
    }

    @Test
    void shouldImportAndArchiveMissingTopics() {
        DesignDocsImportContent content = new DesignDocsImportContent(
            "en",
            "User Flow",
            "Main onboarding flow",
            "# Flow"
        );

        DesignDocsImportTopic importTopic = new DesignDocsImportTopic(
            "user-flow",
            1,
            DesignTopicStatus.ACTIVE,
            List.of(content)
        );

        DesignTopic existing = new DesignTopic();
        existing.setKey("old-design");
        existing.setStatus(DesignTopicStatus.ACTIVE);

        when(topicRepository.findByKey("user-flow")).thenReturn(Optional.empty());
        when(topicRepository.findAll()).thenReturn(List.of(existing));

        DesignDocsImportResultDTO result = importService.applyImport(List.of(importTopic));

        assertEquals(1, result.importedTopics());
        assertEquals(1, result.archivedTopics());
        assertEquals(DesignTopicStatus.ARCHIVED, existing.getStatus());
        verify(topicRepository, times(2)).save(any(DesignTopic.class));
    }
}

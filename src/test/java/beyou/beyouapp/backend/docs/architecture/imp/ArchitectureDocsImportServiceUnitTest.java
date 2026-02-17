package beyou.beyouapp.backend.docs.architecture.imp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import beyou.beyouapp.backend.docs.architecture.ArchitectureTopicRepository;
import beyou.beyouapp.backend.docs.architecture.dto.ArchitectureDocsImportResultDTO;
import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopic;
import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopicStatus;
import beyou.beyouapp.backend.docs.architecture.imp.ArchitectureDocsImportService.ArchitectureDocsImportContent;
import beyou.beyouapp.backend.docs.architecture.imp.ArchitectureDocsImportService.ArchitectureDocsImportTopic;

@ExtendWith(MockitoExtension.class)
public class ArchitectureDocsImportServiceUnitTest {

    @Mock
    private ArchitectureTopicRepository topicRepository;

    @Mock
    private ArchitectureDocsImportParser parser;

    private ArchitectureDocsImportService importService;

    @BeforeEach
    void setup() {
        importService = new ArchitectureDocsImportService(topicRepository, parser);
    }

    @Test
    void shouldImportAndArchiveMissingTopics() {
        ArchitectureDocsImportContent content = new ArchitectureDocsImportContent(
            "en",
            "Overview",
            "Main flow",
            "# Context"
        );

        ArchitectureDocsImportTopic importTopic = new ArchitectureDocsImportTopic(
            "overview",
            1,
            ArchitectureTopicStatus.ACTIVE,
            "flowchart LR",
            List.of(content)
        );

        ArchitectureTopic existing = new ArchitectureTopic();
        existing.setKey("old-topic");
        existing.setStatus(ArchitectureTopicStatus.ACTIVE);

        when(topicRepository.findByKey("overview")).thenReturn(Optional.empty());
        when(topicRepository.findAll()).thenReturn(List.of(existing));

        ArchitectureDocsImportResultDTO result = importService.applyImport(List.of(importTopic));

        assertEquals(1, result.importedTopics());
        assertEquals(1, result.archivedTopics());
        assertEquals(ArchitectureTopicStatus.ARCHIVED, existing.getStatus());
        verify(topicRepository).save(any(ArchitectureTopic.class));
    }
}

package beyou.beyouapp.backend.unit.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import beyou.beyouapp.backend.docs.architecture.ArchitectureTopicRepository;
import beyou.beyouapp.backend.docs.architecture.ArchitectureTopicService;
import beyou.beyouapp.backend.docs.architecture.dto.ArchitectureTopicDetailDTO;
import beyou.beyouapp.backend.docs.architecture.dto.ArchitectureTopicListItemDTO;
import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopic;
import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopicContent;
import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopicStatus;
import beyou.beyouapp.backend.exceptions.docs.DocsTopicNotFound;

@ExtendWith(MockitoExtension.class)
public class ArchitectureTopicServiceUnitTest {
    @Mock
    private ArchitectureTopicRepository topicRepository;

    private ArchitectureTopicService topicService;

    private ArchitectureTopic topic;

    @BeforeEach
    public void setup() {
        topicService = new ArchitectureTopicService(topicRepository);
        topic = buildTopic();
    }

    @Test
    public void shouldListTopicsInLocale() {
        when(topicRepository.findAllByStatusOrderByOrderIndex(ArchitectureTopicStatus.ACTIVE))
            .thenReturn(List.of(topic));

        List<ArchitectureTopicListItemDTO> response = topicService.getTopics("en");

        assertEquals(1, response.size());
        assertEquals("overview", response.get(0).key());
        assertEquals("Overview", response.get(0).title());
    }

    @Test
    public void shouldFallbackToEnglishWhenLocaleMissing() {
        topic.setContents(List.of(buildContent(topic, "en")));

        when(topicRepository.findAllByStatusOrderByOrderIndex(ArchitectureTopicStatus.ACTIVE))
            .thenReturn(List.of(topic));

        List<ArchitectureTopicListItemDTO> response = topicService.getTopics("pt");

        assertEquals(1, response.size());
        assertEquals("Overview", response.get(0).title());
    }

    @Test
    public void shouldGetTopicDetails() {
        when(topicRepository.findByKey("overview")).thenReturn(Optional.of(topic));

        ArchitectureTopicDetailDTO response = topicService.getTopic("overview", "en");

        assertEquals("overview", response.key());
        assertEquals("flowchart LR", response.diagramMermaid());
    }

    @Test
    public void shouldThrowWhenTopicNotFound() {
        when(topicRepository.findByKey("missing")).thenReturn(Optional.empty());

        assertThrows(DocsTopicNotFound.class, () -> topicService.getTopic("missing", "en"));
    }

    @Test
    public void shouldThrowWhenContentMissing() {
        topic.setContents(List.of());
        when(topicRepository.findByKey("overview")).thenReturn(Optional.of(topic));

        assertThrows(DocsTopicNotFound.class, () -> topicService.getTopic("overview", "en"));
    }

    private ArchitectureTopic buildTopic() {
        ArchitectureTopic newTopic = new ArchitectureTopic();
        newTopic.setId(UUID.randomUUID());
        newTopic.setKey("overview");
        newTopic.setOrderIndex(1);
        newTopic.setStatus(ArchitectureTopicStatus.ACTIVE);
        newTopic.setContents(List.of(buildContent(newTopic, "en")));
        return newTopic;
    }

    private ArchitectureTopicContent buildContent(ArchitectureTopic parent, String locale) {
        ArchitectureTopicContent content = new ArchitectureTopicContent();
        content.setId(UUID.randomUUID());
        content.setTopic(parent);
        content.setLocale(locale);
        content.setTitle("Overview");
        content.setSummary("Main flow");
        content.setDiagramMermaid("flowchart LR");
        content.setDocMarkdown("## Context");
        content.setUpdatedAt(Date.valueOf("2026-02-17"));
        return content;
    }
}

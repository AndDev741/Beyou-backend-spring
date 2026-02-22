package beyou.beyouapp.backend.docs.project;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.docs.project.dto.ProjectTopicDetailDTO;
import beyou.beyouapp.backend.docs.project.dto.ProjectTopicListItemDTO;
import beyou.beyouapp.backend.docs.project.entity.ProjectTopic;
import beyou.beyouapp.backend.docs.project.entity.ProjectTopicContent;
import beyou.beyouapp.backend.docs.project.entity.ProjectTopicStatus;
import beyou.beyouapp.backend.exceptions.docs.DocsTopicNotFound;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProjectTopicService {
    private static final String DEFAULT_LOCALE = "en";

    private final ProjectTopicRepository topicRepository;

    @Transactional(readOnly = true)
    public List<ProjectTopicListItemDTO> getTopics(String locale) {
        String normalizedLocale = normalizeLocale(locale);

        return topicRepository.findAllByStatusOrderByOrderIndex(ProjectTopicStatus.ACTIVE)
            .stream()
            .map(topic -> toListItemDTO(topic, normalizedLocale))
            .toList();
    }

    @Transactional(readOnly = true)
    public ProjectTopicDetailDTO getTopic(String key, String locale) {
        String normalizedLocale = normalizeLocale(locale);

        ProjectTopic topic = topicRepository.findByKey(key)
            .orElseThrow(() -> new DocsTopicNotFound("Project topic not found"));

        ProjectTopicContent content = resolveContent(topic, normalizedLocale);

        return new ProjectTopicDetailDTO(
            topic.getKey(),
            content.getTitle(),
            content.getDocMarkdown(),
            content.getDiagramMermaid(),
            content.getDesignTopicKey(),
            content.getArchitectureTopicKey(),
            content.getRepositoryUrl(),
            content.getTags(),
            content.getUpdatedAt()
        );
    }

    private ProjectTopicListItemDTO toListItemDTO(ProjectTopic topic, String locale) {
        ProjectTopicContent content = resolveContent(topic, locale);

        return new ProjectTopicListItemDTO(
            topic.getKey(),
            content.getTitle(),
            content.getSummary(),
            topic.getOrderIndex(),
            content.getUpdatedAt(),
            topic.getStatus().name(),
            content.getTags()
        );
    }

    private ProjectTopicContent resolveContent(ProjectTopic topic, String locale) {
        return topic.findContentByLocale(locale)
            .or(() -> topic.findContentByLocale(DEFAULT_LOCALE))
            .orElseThrow(() -> new DocsTopicNotFound("Project topic content not found"));
    }

    private String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return DEFAULT_LOCALE;
        }

        return locale.trim().toLowerCase();
    }
}
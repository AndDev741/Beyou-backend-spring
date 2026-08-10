package beyou.beyouapp.backend.docs.project;

import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.docs.DocsLocale;
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
    private final ProjectTopicRepository topicRepository;

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "projectsTopics", key = "T(beyou.beyouapp.backend.docs.DocsLocale).normalize(#locale)")
    public List<ProjectTopicListItemDTO> getTopics(String locale) {
        String normalizedLocale = DocsLocale.normalize(locale);

        return topicRepository.findAllByStatusOrderByOrderIndex(ProjectTopicStatus.ACTIVE)
            .stream()
            .map(topic -> toListItemDTO(topic, normalizedLocale))
            .toList();
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "projectsTopic", key = "#key + '_' + T(beyou.beyouapp.backend.docs.DocsLocale).normalize(#locale)")
    public ProjectTopicDetailDTO getTopic(String key, String locale) {
        String normalizedLocale = DocsLocale.normalize(locale);

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
            .or(() -> topic.findContentByLocale(DocsLocale.DEFAULT_LOCALE))
            .orElseThrow(() -> new DocsTopicNotFound("Project topic content not found"));
    }

}
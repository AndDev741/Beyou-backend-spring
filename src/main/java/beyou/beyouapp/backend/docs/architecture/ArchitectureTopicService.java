package beyou.beyouapp.backend.docs.architecture;

import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.docs.DocsLocale;
import beyou.beyouapp.backend.docs.architecture.dto.ArchitectureTopicDetailDTO;
import beyou.beyouapp.backend.docs.architecture.dto.ArchitectureTopicListItemDTO;
import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopic;
import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopicContent;
import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopicStatus;
import beyou.beyouapp.backend.exceptions.docs.DocsTopicNotFound;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ArchitectureTopicService {
    private final ArchitectureTopicRepository topicRepository;

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "architectureTopics", key = "T(beyou.beyouapp.backend.docs.DocsLocale).normalize(#locale)")
    public List<ArchitectureTopicListItemDTO> getTopics(String locale) {
        String normalizedLocale = DocsLocale.normalize(locale);

        return topicRepository.findAllByStatusOrderByOrderIndex(ArchitectureTopicStatus.ACTIVE)
            .stream()
            .map(topic -> toListItemDTO(topic, normalizedLocale))
            .toList();
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "architectureTopic", key = "#key + '_' + T(beyou.beyouapp.backend.docs.DocsLocale).normalize(#locale)")
    public ArchitectureTopicDetailDTO getTopic(String key, String locale) {
        String normalizedLocale = DocsLocale.normalize(locale);

        ArchitectureTopic topic = topicRepository.findByKey(key)
            .orElseThrow(() -> new DocsTopicNotFound("Architecture topic not found"));

        ArchitectureTopicContent content = resolveContent(topic, normalizedLocale);

        return new ArchitectureTopicDetailDTO(
            topic.getKey(),
            content.getTitle(),
            content.getDiagramMermaid(),
            content.getDocMarkdown(),
            content.getUpdatedAt(),
            topic.getStatus().name(),
            topic.getTags(),
            topic.getProjectKey()
        );
    }

    private ArchitectureTopicListItemDTO toListItemDTO(ArchitectureTopic topic, String locale) {
        ArchitectureTopicContent content = resolveContent(topic, locale);

        return new ArchitectureTopicListItemDTO(
            topic.getKey(),
            content.getTitle(),
            content.getSummary(),
            topic.getOrderIndex(),
            content.getUpdatedAt(),
            topic.getStatus().name(),
            topic.getTags(),
            topic.getProjectKey()
        );
    }

    private ArchitectureTopicContent resolveContent(ArchitectureTopic topic, String locale) {
        return topic.findContentByLocale(locale)
            .or(() -> topic.findContentByLocale(DocsLocale.DEFAULT_LOCALE))
            .orElseThrow(() -> new DocsTopicNotFound("Architecture topic content not found"));
    }
}

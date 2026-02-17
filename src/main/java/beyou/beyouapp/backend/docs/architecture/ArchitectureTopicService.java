package beyou.beyouapp.backend.docs.architecture;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private static final String DEFAULT_LOCALE = "en";

    private final ArchitectureTopicRepository topicRepository;

    @Transactional(readOnly = true)
    public List<ArchitectureTopicListItemDTO> getTopics(String locale) {
        String normalizedLocale = normalizeLocale(locale);

        return topicRepository.findAllByStatusOrderByOrderIndex(ArchitectureTopicStatus.ACTIVE)
            .stream()
            .map(topic -> toListItemDTO(topic, normalizedLocale))
            .toList();
    }

    @Transactional(readOnly = true)
    public ArchitectureTopicDetailDTO getTopic(String key, String locale) {
        String normalizedLocale = normalizeLocale(locale);

        ArchitectureTopic topic = topicRepository.findByKey(key)
            .orElseThrow(() -> new DocsTopicNotFound("Architecture topic not found"));

        ArchitectureTopicContent content = resolveContent(topic, normalizedLocale);

        return new ArchitectureTopicDetailDTO(
            topic.getKey(),
            content.getTitle(),
            content.getDiagramMermaid(),
            content.getDocMarkdown(),
            content.getUpdatedAt()
        );
    }

    private ArchitectureTopicListItemDTO toListItemDTO(ArchitectureTopic topic, String locale) {
        ArchitectureTopicContent content = resolveContent(topic, locale);

        return new ArchitectureTopicListItemDTO(
            topic.getKey(),
            content.getTitle(),
            content.getSummary(),
            topic.getOrderIndex(),
            content.getUpdatedAt()
        );
    }

    private ArchitectureTopicContent resolveContent(ArchitectureTopic topic, String locale) {
        return topic.findContentByLocale(locale)
            .or(() -> topic.findContentByLocale(DEFAULT_LOCALE))
            .orElseThrow(() -> new DocsTopicNotFound("Architecture topic content not found"));
    }

    private String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return DEFAULT_LOCALE;
        }

        return locale.trim().toLowerCase();
    }
}

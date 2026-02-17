package beyou.beyouapp.backend.docs.design;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.docs.design.dto.DesignTopicDetailDTO;
import beyou.beyouapp.backend.docs.design.dto.DesignTopicListItemDTO;
import beyou.beyouapp.backend.docs.design.entity.DesignTopic;
import beyou.beyouapp.backend.docs.design.entity.DesignTopicContent;
import beyou.beyouapp.backend.docs.design.entity.DesignTopicStatus;
import beyou.beyouapp.backend.exceptions.docs.DocsDesignNotFound;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DesignTopicService {
    private static final String DEFAULT_LOCALE = "en";

    private final DesignTopicRepository topicRepository;

    @Transactional(readOnly = true)
    public List<DesignTopicListItemDTO> getTopics(String locale) {
        String normalizedLocale = normalizeLocale(locale);

        return topicRepository.findAllByStatusOrderByOrderIndex(DesignTopicStatus.ACTIVE)
            .stream()
            .map(topic -> toListItemDTO(topic, normalizedLocale))
            .toList();
    }

    @Transactional(readOnly = true)
    public DesignTopicDetailDTO getTopic(String key, String locale) {
        String normalizedLocale = normalizeLocale(locale);

        DesignTopic topic = topicRepository.findByKey(key)
            .orElseThrow(() -> new DocsDesignNotFound("Design topic not found"));

        DesignTopicContent content = resolveContent(topic, normalizedLocale);

        return new DesignTopicDetailDTO(
            topic.getKey(),
            content.getTitle(),
            content.getDocMarkdown(),
            content.getUpdatedAt()
        );
    }

    private DesignTopicListItemDTO toListItemDTO(DesignTopic topic, String locale) {
        DesignTopicContent content = resolveContent(topic, locale);

        return new DesignTopicListItemDTO(
            topic.getKey(),
            content.getTitle(),
            content.getSummary(),
            topic.getOrderIndex(),
            content.getUpdatedAt()
        );
    }

    private DesignTopicContent resolveContent(DesignTopic topic, String locale) {
        return topic.findContentByLocale(locale)
            .or(() -> topic.findContentByLocale(DEFAULT_LOCALE))
            .orElseThrow(() -> new DocsDesignNotFound("Design topic content not found"));
    }

    private String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return DEFAULT_LOCALE;
        }

        return locale.trim().toLowerCase();
    }
}

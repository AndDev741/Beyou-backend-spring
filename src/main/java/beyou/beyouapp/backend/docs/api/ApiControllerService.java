package beyou.beyouapp.backend.docs.api;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.docs.api.dto.ApiControllerDetailDTO;
import beyou.beyouapp.backend.docs.api.dto.ApiControllerListItemDTO;
import beyou.beyouapp.backend.docs.api.entity.ApiControllerTopic;
import beyou.beyouapp.backend.docs.api.entity.ApiControllerContent;
import beyou.beyouapp.backend.docs.api.entity.ApiControllerStatus;
import beyou.beyouapp.backend.exceptions.docs.DocsTopicNotFound;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ApiControllerService {
    private static final String DEFAULT_LOCALE = "en";

    private final ApiControllerTopicRepository topicRepository;

    @Transactional(readOnly = true)
    public List<ApiControllerListItemDTO> getTopics(String locale) {
        String normalizedLocale = normalizeLocale(locale);

        return topicRepository.findAllByStatusOrderByOrderIndex(ApiControllerStatus.ACTIVE)
            .stream()
            .map(topic -> toListItemDTO(topic, normalizedLocale))
            .toList();
    }

    @Transactional(readOnly = true)
    public ApiControllerDetailDTO getTopic(String key, String locale) {
        String normalizedLocale = normalizeLocale(locale);

        ApiControllerTopic topic = topicRepository.findByKey(key)
            .orElseThrow(() -> new DocsTopicNotFound("API controller topic not found"));

        ApiControllerContent content = resolveContent(topic, normalizedLocale);

        return new ApiControllerDetailDTO(
            topic.getKey(),
            content.getTitle(),
            content.getSummary(),
            content.getApiCatalog(),
            content.getUpdatedAt()
        );
    }

    private ApiControllerListItemDTO toListItemDTO(ApiControllerTopic topic, String locale) {
        ApiControllerContent content = resolveContent(topic, locale);

        return new ApiControllerListItemDTO(
            topic.getKey(),
            content.getTitle(),
            content.getSummary(),
            topic.getOrderIndex(),
            content.getUpdatedAt()
        );
    }

    private ApiControllerContent resolveContent(ApiControllerTopic topic, String locale) {
        return topic.findContentByLocale(locale)
            .or(() -> topic.findContentByLocale(DEFAULT_LOCALE))
            .orElseThrow(() -> new DocsTopicNotFound("API controller topic content not found"));
    }

    private String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return DEFAULT_LOCALE;
        }

        return locale.trim().toLowerCase();
    }
}
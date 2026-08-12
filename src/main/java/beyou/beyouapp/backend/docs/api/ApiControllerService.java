package beyou.beyouapp.backend.docs.api;

import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.docs.DocsLocale;
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
    private final ApiControllerTopicRepository topicRepository;

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "apiTopics", key = "T(beyou.beyouapp.backend.docs.DocsLocale).normalize(#locale)")
    public List<ApiControllerListItemDTO> getTopics(String locale) {
        String normalizedLocale = DocsLocale.normalize(locale);

        return topicRepository.findAllByStatusOrderByOrderIndex(ApiControllerStatus.ACTIVE)
            .stream()
            .map(topic -> toListItemDTO(topic, normalizedLocale))
            .toList();
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "apiTopic", key = "#key + '_' + T(beyou.beyouapp.backend.docs.DocsLocale).normalize(#locale)")
    public ApiControllerDetailDTO getTopic(String key, String locale) {
        String normalizedLocale = DocsLocale.normalize(locale);

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
            .or(() -> topic.findContentByLocale(DocsLocale.DEFAULT_LOCALE))
            .orElseThrow(() -> new DocsTopicNotFound("API controller topic content not found"));
    }

}
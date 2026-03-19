package beyou.beyouapp.backend.docs.blog;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.docs.blog.dto.BlogTopicDetailDTO;
import beyou.beyouapp.backend.docs.blog.dto.BlogTopicListItemDTO;
import beyou.beyouapp.backend.docs.blog.entity.BlogTopic;
import beyou.beyouapp.backend.docs.blog.entity.BlogTopicCategory;
import beyou.beyouapp.backend.docs.blog.entity.BlogTopicContent;
import beyou.beyouapp.backend.docs.blog.entity.BlogTopicStatus;
import beyou.beyouapp.backend.exceptions.docs.DocsBlogNotFound;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BlogTopicService {
    private static final String DEFAULT_LOCALE = "en";

    private final BlogTopicRepository topicRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<BlogTopicListItemDTO> getTopics(String locale, String category, String tag) {
        String normalizedLocale = normalizeLocale(locale);
        BlogTopicCategory parsedCategory = parseCategory(category);

        return topicRepository.findAllByStatusOrderByPublishedDesc(BlogTopicStatus.ACTIVE)
            .stream()
            .filter(topic -> parsedCategory == null || topic.getCategory() == parsedCategory)
            .filter(topic -> tag == null || tag.isBlank() || (topic.getTags() != null && topic.getTags().contains(tag)))
            .map(topic -> toListItemDTO(topic, normalizedLocale))
            .toList();
    }

    @Transactional(readOnly = true)
    public BlogTopicDetailDTO getTopic(String key, String locale) {
        String normalizedLocale = normalizeLocale(locale);

        BlogTopic topic = topicRepository.findByKey(key)
            .orElseThrow(() -> new DocsBlogNotFound("Blog topic not found"));

        BlogTopicContent content = resolveContent(topic, normalizedLocale);

        return new BlogTopicDetailDTO(
            topic.getKey(),
            content.getTitle(),
            content.getDocMarkdown(),
            topic.getCategory().name(),
            serializeTags(topic.getTags()),
            topic.getFeatured(),
            topic.getPublishedAt(),
            topic.getCoverColor(),
            topic.getCoverEmoji(),
            topic.getAuthor(),
            content.getUpdatedAt()
        );
    }

    private BlogTopicListItemDTO toListItemDTO(BlogTopic topic, String locale) {
        BlogTopicContent content = resolveContent(topic, locale);

        return new BlogTopicListItemDTO(
            topic.getKey(),
            content.getTitle(),
            content.getSummary(),
            topic.getCategory().name(),
            serializeTags(topic.getTags()),
            topic.getFeatured(),
            topic.getPublishedAt(),
            topic.getCoverColor(),
            topic.getCoverEmoji(),
            topic.getAuthor(),
            content.getUpdatedAt()
        );
    }

    private BlogTopicContent resolveContent(BlogTopic topic, String locale) {
        return topic.findContentByLocale(locale)
            .or(() -> topic.findContentByLocale(DEFAULT_LOCALE))
            .orElseThrow(() -> new DocsBlogNotFound("Blog topic content not found"));
    }

    private String serializeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private BlogTopicCategory parseCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        try {
            return BlogTopicCategory.valueOf(category.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return DEFAULT_LOCALE;
        }
        return locale.trim().toLowerCase();
    }
}

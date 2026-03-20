package beyou.beyouapp.backend.docs.blog.imp;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.docs.blog.entity.BlogTopicCategory;
import beyou.beyouapp.backend.docs.blog.entity.BlogTopicStatus;
import beyou.beyouapp.backend.exceptions.docs.DocsImportFailed;

@Component
public class BlogDocsImportParser {

    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public BlogTopicMetadata parseTopicYaml(String content, String fallbackKey) {
        if (content == null || content.isBlank()) {
            throw new DocsImportFailed("topic.yaml is empty");
        }

        Yaml yaml = new Yaml();
        Map<String, Object> values = yaml.load(content);
        if (values == null) {
            throw new DocsImportFailed("topic.yaml parsed to null");
        }

        String key = stringValueOrFallback(values.get("key"), fallbackKey);
        if (key == null || key.isBlank()) {
            throw new DocsImportFailed("topic.yaml missing key");
        }

        BlogTopicStatus status = parseStatus(stringValue(values.get("status")));
        BlogTopicCategory category = parseCategory(stringValue(values.get("category")));

        String tags = parseTags(values.get("tags"));
        boolean featured = booleanValue(values.get("featured"), false);
        Date publishedAt = parsePublishedAt(values.get("publishedAt"));
        String coverColor = parseColor(stringValue(values.get("coverColor")));
        String coverEmoji = stringValue(values.get("coverEmoji"));
        String author = stringValue(values.get("author"));

        return new BlogTopicMetadata(
            key.trim(), status, category, tags, featured, publishedAt, coverColor, coverEmoji, author
        );
    }

    public MarkdownContent parseMarkdown(String content) {
        if (content == null || content.isBlank()) {
            throw new DocsImportFailed("markdown content is empty");
        }

        List<String> lines = List.of(content.split("\\r?\\n", -1));
        if (!lines.isEmpty() && lines.get(0).trim().equals("---")) {
            return parseWithFrontMatter(lines);
        }

        return new MarkdownContent(null, null, content.trim());
    }

    private MarkdownContent parseWithFrontMatter(List<String> lines) {
        Map<String, String> values = new HashMap<>();
        int index = 1;
        for (; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.trim().equals("---")) {
                break;
            }
            addYamlLine(values, line);
        }

        if (index >= lines.size()) {
            throw new DocsImportFailed("front-matter is missing closing ---");
        }

        String body = String.join("\n", lines.subList(index + 1, lines.size())).trim();

        return new MarkdownContent(
            emptyToNull(values.get("title")),
            emptyToNull(values.get("summary")),
            body
        );
    }

    private void addYamlLine(Map<String, String> values, String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isBlank() || trimmed.startsWith("#")) {
            return;
        }
        int separatorIndex = trimmed.indexOf(":");
        if (separatorIndex < 0) {
            return;
        }
        String yamlKey = trimmed.substring(0, separatorIndex).trim();
        String value = trimmed.substring(separatorIndex + 1).trim();
        values.put(yamlKey, stripQuotes(value));
    }

    private String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private BlogTopicStatus parseStatus(String statusValue) {
        if (statusValue == null || statusValue.isBlank()) {
            return BlogTopicStatus.ACTIVE;
        }
        try {
            return BlogTopicStatus.valueOf(statusValue.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new DocsImportFailed("Invalid status: " + statusValue);
        }
    }

    private BlogTopicCategory parseCategory(String categoryValue) {
        if (categoryValue == null || categoryValue.isBlank()) {
            return BlogTopicCategory.TECHNICAL;
        }
        try {
            return BlogTopicCategory.valueOf(categoryValue.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new DocsImportFailed("Invalid category: " + categoryValue);
        }
    }

    @SuppressWarnings("unchecked")
    private String parseTags(Object tagsValue) {
        if (tagsValue == null) {
            return null;
        }
        if (tagsValue instanceof List<?> list) {
            try {
                return objectMapper.writeValueAsString(list);
            } catch (JsonProcessingException e) {
                throw new DocsImportFailed("Failed to serialize tags to JSON");
            }
        }
        if (tagsValue instanceof String str) {
            return str.isBlank() ? null : str;
        }
        return tagsValue.toString();
    }

    private boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String str = value.toString().trim().toLowerCase();
        return "true".equals(str);
    }

    private Date parsePublishedAt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.util.Date date) {
            return new Date(date.getTime());
        }
        String str = value.toString().trim();
        if (str.isBlank()) {
            return null;
        }
        try {
            LocalDate localDate = LocalDate.parse(str);
            return Date.valueOf(localDate);
        } catch (DateTimeParseException ex) {
            throw new DocsImportFailed("Invalid publishedAt date: " + value);
        }
    }

    private String parseColor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (HEX_COLOR_PATTERN.matcher(value.trim()).matches()) {
            return value.trim();
        }
        return null;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    private String stringValueOrFallback(Object value, String fallback) {
        String str = stringValue(value);
        if (str == null || str.isBlank()) {
            return fallback;
        }
        return str;
    }

    private String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    public record BlogTopicMetadata(
        String key, BlogTopicStatus status, BlogTopicCategory category,
        String tags, boolean featured, java.sql.Date publishedAt,
        String coverColor, String coverEmoji, String author
    ) {}

    public record MarkdownContent(String title, String summary, String body) {}
}

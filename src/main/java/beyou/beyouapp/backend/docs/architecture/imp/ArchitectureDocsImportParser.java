package beyou.beyouapp.backend.docs.architecture.imp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopicStatus;
import beyou.beyouapp.backend.exceptions.docs.DocsImportFailed;

@Component
public class ArchitectureDocsImportParser {

    public ArchitectureTopicMetadata parseTopicYaml(String content, String fallbackKey) {
        if (content == null || content.isBlank()) {
            throw new DocsImportFailed("topic.yaml is empty");
        }

        Map<String, String> values = parseSimpleYaml(content);

        String key = valueOrFallback(values.get("key"), fallbackKey);
        if (key == null || key.isBlank()) {
            throw new DocsImportFailed("topic.yaml missing key");
        }

        int orderIndex = parseIntOrDefault(values.get("orderIndex"), 0);
        ArchitectureTopicStatus status = parseStatus(values.get("status"));

        return new ArchitectureTopicMetadata(key.trim(), orderIndex, status);
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

    private Map<String, String> parseSimpleYaml(String content) {
        Map<String, String> values = new HashMap<>();
        for (String line : content.split("\\r?\\n")) {
            addYamlLine(values, line);
        }
        return values;
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
        String key = trimmed.substring(0, separatorIndex).trim();
        String value = trimmed.substring(separatorIndex + 1).trim();
        values.put(key, stripQuotes(value));
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

    private ArchitectureTopicStatus parseStatus(String statusValue) {
        if (statusValue == null || statusValue.isBlank()) {
            return ArchitectureTopicStatus.ACTIVE;
        }

        try {
            return ArchitectureTopicStatus.valueOf(statusValue.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new DocsImportFailed("Invalid status: " + statusValue);
        }
    }

    private int parseIntOrDefault(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new DocsImportFailed("Invalid orderIndex: " + value);
        }
    }

    private String valueOrFallback(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    public record ArchitectureTopicMetadata(String key, int orderIndex, ArchitectureTopicStatus status) {}

    public record MarkdownContent(String title, String summary, String body) {}
}

package beyou.beyouapp.backend.docs.api.imp;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

import org.yaml.snakeyaml.Yaml;
import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.docs.api.entity.ApiControllerStatus;
import beyou.beyouapp.backend.exceptions.docs.DocsImportFailed;

@Component
public class ApiDocsImportParser {

    public ApiControllerMetadata parseControllerYaml(String content, String fallbackKey) {
        if (content == null || content.isBlank()) {
            throw new DocsImportFailed("controller.yaml is empty");
        }

        Map<String, String> values = parseSimpleYaml(content);

        String key = valueOrFallback(values.get("key"), fallbackKey);
        if (key == null || key.isBlank()) {
            throw new DocsImportFailed("controller.yaml missing key");
        }

        int orderIndex = parseIntOrDefault(values.get("orderIndex"), 0);
        ApiControllerStatus status = parseStatus(values.get("status"));

        return new ApiControllerMetadata(key.trim(), orderIndex, status);
    }

    public OpenApiContent parseOpenApi(String content) {
        if (content == null || content.isBlank()) {
            throw new DocsImportFailed("OpenAPI content is empty");
        }

        String title = null;
        String summary = null;
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> parsed = yaml.load(content);
            if (parsed != null) {
                Map<String, Object> info = (Map<String, Object>) parsed.get("info");
                if (info != null) {
                    title = (String) info.get("title");
                    summary = (String) info.get("description");
                }
            }
        } catch (Exception ex) {
            // ignore parsing errors, keep title/summary null
        }

        return new OpenApiContent(title, summary, content.trim());
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

    private String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
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

    private ApiControllerStatus parseStatus(String statusValue) {
        if (statusValue == null || statusValue.isBlank()) {
            return ApiControllerStatus.ACTIVE;
        }

        try {
            return ApiControllerStatus.valueOf(statusValue.trim().toUpperCase());
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

    public record ApiControllerMetadata(String key, int orderIndex, ApiControllerStatus status) {}

    public record MarkdownContent(String title, String summary, String body) {}

    public record OpenApiContent(String title, String summary, String spec) {}
}
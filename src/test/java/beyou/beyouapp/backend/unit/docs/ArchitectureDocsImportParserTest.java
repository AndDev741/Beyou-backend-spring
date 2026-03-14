package beyou.beyouapp.backend.unit.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.docs.architecture.entity.ArchitectureTopicStatus;
import beyou.beyouapp.backend.docs.architecture.imp.ArchitectureDocsImportParser;
import beyou.beyouapp.backend.docs.architecture.imp.ArchitectureDocsImportParser.ArchitectureTopicMetadata;
import beyou.beyouapp.backend.docs.architecture.imp.ArchitectureDocsImportParser.MarkdownContent;

public class ArchitectureDocsImportParserTest {

    private final ArchitectureDocsImportParser parser = new ArchitectureDocsImportParser();

    @Test
    void shouldParseTopicYaml() {
        String yaml = "key: overview\norderIndex: 2\nstatus: DRAFT\n";

        ArchitectureTopicMetadata metadata = parser.parseTopicYaml(yaml, "fallback");

        assertEquals("overview", metadata.key());
        assertEquals(2, metadata.orderIndex());
        assertEquals(ArchitectureTopicStatus.DRAFT, metadata.status());
        assertTrue(metadata.tags().isEmpty());
        assertNull(metadata.projectKey());
    }

    @Test
    void shouldParseTopicYamlWithTags() {
        String yaml = "key: overview\norderIndex: 1\nstatus: ACTIVE\ntags:\n  - system-design\n  - spring\n  - react\nprojectKey: beyou-backend\n";

        ArchitectureTopicMetadata metadata = parser.parseTopicYaml(yaml, "fallback");

        assertEquals("overview", metadata.key());
        assertEquals(List.of("system-design", "spring", "react"), metadata.tags());
        assertEquals("beyou-backend", metadata.projectKey());
    }

    @Test
    void shouldParseTopicYamlWithNullProjectKey() {
        String yaml = "key: overview\norderIndex: 1\nstatus: ACTIVE\nprojectKey: null\n";

        ArchitectureTopicMetadata metadata = parser.parseTopicYaml(yaml, "fallback");

        assertNull(metadata.projectKey());
    }

    @Test
    void shouldParseMarkdownFrontMatter() {
        String markdown = "---\n" +
            "title: \"Overview\"\n" +
            "summary: Main flow\n" +
            "---\n" +
            "# Context\n" +
            "Details";

        MarkdownContent content = parser.parseMarkdown(markdown);

        assertEquals("Overview", content.title());
        assertEquals("Main flow", content.summary());
        assertEquals("# Context\nDetails", content.body());
    }

    @Test
    void shouldParseMarkdownWithoutFrontMatter() {
        String markdown = "# Title\nBody";

        MarkdownContent content = parser.parseMarkdown(markdown);

        assertNull(content.title());
        assertNull(content.summary());
        assertEquals("# Title\nBody", content.body());
    }
}

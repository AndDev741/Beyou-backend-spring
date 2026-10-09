package beyou.beyouapp.backend.unit.notebook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.notebook.BlockText;
import beyou.beyouapp.backend.domain.notebook.MarkdownBlocks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/** "Save to page": AI markdown into BlockNote blocks. */
class MarkdownBlocksTest {

    @Test
    void headingsListsCodeAndParagraphsBecomeTheirBlocks() {
        ArrayNode blocks = MarkdownBlocks.toBlocks("""
                ## Deletion
                Three cases.
                - No children
                1. First
                ```java
                int x = 1;
                ```
                """);

        assertThat(blocks).extracting(b -> b.get("type").asString())
                .containsExactly("heading", "paragraph", "bulletListItem", "numberedListItem", "codeBlock");
        assertThat(blocks.get(0).get("props").get("level").asInt()).isEqualTo(2);
        assertThat(blocks.get(4).get("props").get("language").asString()).isEqualTo("java");
        assertThat(blocks.get(4).get("content").get(0).get("text").asString()).isEqualTo("int x = 1;");
    }

    @Test
    void inlineBoldAndCodeKeepTheirStyles() {
        JsonNode parts = MarkdownBlocks.toBlocks("Use the **successor** in `delete`.").get(0).get("content");

        assertThat(parts.get(1).get("text").asString()).isEqualTo("successor");
        assertThat(parts.get(1).get("styles").get("bold").asBoolean()).isTrue();
        assertThat(parts.get(3).get("styles").get("code").asBoolean()).isTrue();
    }

    /** A [2] points into a chat's source list; on a page there is no list for it to point at. */
    @Test
    void citationMarkersAreRemoved() {
        String text = MarkdownBlocks.toBlocks("It has no left child [1, 3]. True [2].")
                .get(0).get("content").get(0).get("text").asString();

        assertThat(text).isEqualTo("It has no left child. True.");
    }

    @Test
    void appendingKeepsWhatWasThereAndReplacesAnUnreadableDocument() {
        String existing = "[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Mine\",\"styles\":{}}]}]";

        assertThat(BlockText.extract(MarkdownBlocks.append(existing, "Added"))).isEqualTo("Mine\nAdded");
        assertThat(BlockText.extract(MarkdownBlocks.append("{broken", "Added"))).isEqualTo("Added");
    }

    @Test
    void aBoardDocumentEndsWithTheBoardBlock() {
        String doc = MarkdownBlocks.boardDocument("Why this matters");

        assertThat(doc).contains("\"type\":\"" + MarkdownBlocks.BOARD_BLOCK_TYPE + "\"");
        assertThat(BlockText.extract(doc)).isEqualTo("Why this matters");
    }

    /**
     * Editors merge a page block by block, by id. A block the server writes without one could
     * only be matched by its text, so every block written here carries its own.
     */
    @Test
    void everyBlockTheServerWritesHasItsOwnId() {
        String doc = MarkdownBlocks.withBlock(
                MarkdownBlocks.append(MarkdownBlocks.boardDocument("Intro"), "# Title\n\n- one\n- two\n\nText"),
                MarkdownBlocks.CARDS_BLOCK_TYPE);

        java.util.List<String> ids = new java.util.ArrayList<>();
        tools.jackson.databind.json.JsonMapper.builder().build().readTree(doc).forEach(block -> ids.add(block.path("id").asString()));

        assertThat(ids).hasSize(7).doesNotContain("").doesNotHaveDuplicates();
    }
}

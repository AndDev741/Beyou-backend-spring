package beyou.beyouapp.backend.unit.notebook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.notebook.BlockText;

/** The text the AI reads, derived from BlockNote JSON. */
class BlockTextTest {

    @Test
    void readsNestedBlocksLinksAndTables() {
        String doc = """
                [{"type":"bulletListItem","content":[{"type":"text","text":"Parent","styles":{}}],
                  "children":[{"type":"bulletListItem","content":[{"type":"text","text":"Child","styles":{}}]}]},
                 {"type":"paragraph","content":[{"type":"link","href":"https://x","content":[{"type":"text","text":"a link","styles":{}}]}]},
                 {"type":"table","content":{"type":"tableContent","rows":[
                   {"cells":[[{"type":"text","text":"Array","styles":{}}],[{"type":"text","text":"O(1)","styles":{}}]]}]}}]
                """;

        assertThat(BlockText.extract(doc)).isEqualTo("Parent\nChild\na link\nArray | O(1)");
    }

    /** A broken document must not lose the save: the page keeps the JSON, the AI just sees nothing. */
    @Test
    void anUnreadableDocumentIsEmptyTextNotAnError() {
        assertThat(BlockText.extract("{not json")).isEmpty();
        assertThat(BlockText.extract(null)).isEmpty();
    }

    @Test
    void aCodeBlockWithStringContentIsRead() {
        assertThat(BlockText.extract("[{\"type\":\"codeBlock\",\"content\":\"int x = 1;\"}]")).isEqualTo("int x = 1;");
    }
}

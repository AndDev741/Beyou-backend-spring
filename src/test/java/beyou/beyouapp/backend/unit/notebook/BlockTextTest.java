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

    /**
     * A diagram keeps its words in a JSON string prop, where the structural walk cannot see them.
     * Read by type, it gives its title, a line per node with its detail, and its link labels, so the
     * AI context, page text and export see a diagram like any other text.
     */
    @Test
    void aDiagramBlockGivesItsTitleNodesAndLinkLabels() {
        String spec = """
                {"v":1,"kind":"FLOW","title":"Login","nodes":[
                  {"id":"n1","label":"Password","detail":null,"tone":"ACCENT"},
                  {"id":"n2","label":"Session","detail":"cookie, 15 minutes","tone":"SUCCESS"},
                  {"id":"n3","label":"  ","detail":"blank label, skipped","tone":"NEUTRAL"}],
                 "links":[{"from":"n1","to":"n2","label":"match","dashed":false},{"from":"n2","to":"n1","label":null}]}
                """;
        String doc = "[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Before\",\"styles\":{}}]},"
                + "{\"id\":\"d\",\"type\":\"diagram\",\"props\":{\"spec\":" + quoted(spec) + "},\"children\":[]}]";

        assertThat(BlockText.extract(doc)).isEqualTo("Before\nLogin\nPassword\nSession: cookie, 15 minutes\nmatch");
    }

    /** A diagram whose prop does not parse contributes nothing, and the rest of the page is still read. */
    @Test
    void aBrokenDiagramPropIsSkipped() {
        String doc = "[{\"type\":\"diagram\",\"props\":{\"spec\":\"{not json\"}},"
                + "{\"type\":\"diagram\",\"props\":{}},"
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"After\",\"styles\":{}}]}]";

        assertThat(BlockText.extract(doc)).isEqualTo("After");
    }

    private static String quoted(String json) {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(json);
    }
}

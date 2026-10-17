package beyou.beyouapp.backend.domain.notebook;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The plain text of a BlockNote document.
 *
 * <p>This is what the AI reads as "what the page says" and what page search matches, so it is
 * worked out here, on the server, from the stored JSON. A client could send any text it liked
 * alongside the document; deriving it means the two can never disagree.
 *
 * <p>The walk is structural, not tied to block types. Any object with a textual {@code text}
 * field is inline text, any {@code content} or {@code children} is walked, and a table's
 * {@code rows} are walked cell by cell. A block type added to the editor later is still read
 * correctly, and a custom block with no text (the roadmap board) contributes nothing.
 *
 * <p>One custom block is read by type: a diagram ({@link MarkdownBlocks#DIAGRAM_BLOCK_TYPE}) keeps
 * its words in a JSON string prop, where the walk cannot see them. It contributes its title, a
 * line per node ({@code label: detail}, or the label alone) and its link labels, so a diagram is
 * found by search, read by the AI and carried by the export like any other text on the page.
 *
 * <p>A document that does not parse yields an empty string rather than an error. The page is
 * still saved: losing the text the AI sees is better than losing what the person typed.
 */
public final class BlockText {

    /** Longest text kept. Past this the page is a book, and the AI sees its beginning. */
    public static final int MAX_LENGTH = 200_000;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private BlockText() {}

    public static String extract(String documentJson) {
        if (documentJson == null || documentJson.isBlank()) {
            return "";
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(documentJson);
        } catch (RuntimeException unreadable) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        blocks(root, out);
        String text = out.toString().replaceAll("\n{3,}", "\n\n").strip();
        return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
    }

    private static void blocks(JsonNode node, StringBuilder out) {
        if (node == null) return;
        if (node.isArray()) {
            for (JsonNode block : node) {
                block(block, out);
            }
        } else if (node.isObject()) {
            block(node, out);
        }
    }

    private static void block(JsonNode block, StringBuilder out) {
        if (block == null || !block.isObject()) return;
        if (isDiagram(block)) {
            diagram(block.get("props"), out);
            blocks(block.get("children"), out);
            return;
        }
        int before = out.length();
        inline(block.get("content"), out);
        if (out.length() > before) {
            out.append('\n');
        }
        blocks(block.get("children"), out);
    }

    private static boolean isDiagram(JsonNode block) {
        JsonNode type = block.get("type");
        return type != null && type.isString() && MarkdownBlocks.DIAGRAM_BLOCK_TYPE.equals(type.asString());
    }

    /** A diagram's words, from the spec in its props. A spec that does not parse contributes nothing. */
    private static void diagram(JsonNode props, StringBuilder out) {
        if (props == null || !props.isObject()) return;
        JsonNode spec = props.get("spec");
        if (spec == null || !spec.isString()) return;
        JsonNode root;
        try {
            root = MAPPER.readTree(spec.asString());
        } catch (RuntimeException unreadable) {
            return;
        }
        if (root == null || !root.isObject()) return;
        line(out, text(root.get("title")));
        for (JsonNode node : items(root.get("nodes"))) {
            String label = text(node.get("label"));
            if (label.isEmpty()) continue;
            String detail = text(node.get("detail"));
            line(out, detail.isEmpty() ? label : label + ": " + detail);
        }
        for (JsonNode link : items(root.get("links"))) {
            line(out, text(link.get("label")));
        }
    }

    private static Iterable<JsonNode> items(JsonNode array) {
        return array != null && array.isArray() ? array : List.of();
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.asString().strip() : "";
    }

    private static void line(StringBuilder out, String text) {
        if (!text.isEmpty()) out.append(text).append('\n');
    }

    private static void inline(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) return;
        if (node.isString()) {
            // A code block can store its content as a bare string.
            out.append(node.asString());
            return;
        }
        if (node.isArray()) {
            for (JsonNode part : node) {
                inline(part, out);
            }
            return;
        }
        if (!node.isObject()) return;
        JsonNode text = node.get("text");
        if (text != null && text.isString()) {
            out.append(text.asString());
        }
        JsonNode rows = node.get("rows");
        if (rows != null && rows.isArray()) {
            for (JsonNode row : rows) {
                JsonNode cells = row.get("cells");
                if (cells == null || !cells.isArray()) continue;
                boolean first = true;
                for (JsonNode cell : cells) {
                    if (!first) out.append(" | ");
                    first = false;
                    inline(cell, out);
                }
                out.append('\n');
            }
        }
        JsonNode content = node.get("content");
        if (content != null && content != node) {
            inline(content, out);
        }
    }
}

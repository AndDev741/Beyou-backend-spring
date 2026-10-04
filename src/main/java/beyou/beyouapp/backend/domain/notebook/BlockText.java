package beyou.beyouapp.backend.domain.notebook;

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
        int before = out.length();
        inline(block.get("content"), out);
        if (out.length() > before) {
            out.append('\n');
        }
        blocks(block.get("children"), out);
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

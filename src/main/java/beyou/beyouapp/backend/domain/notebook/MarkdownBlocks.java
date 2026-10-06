package beyou.beyouapp.backend.domain.notebook;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Markdown written by the AI, turned into BlockNote blocks that can be appended to a page.
 *
 * <p>"Save to page" happens from the study room, where no editor is mounted, so the conversion
 * has to live somewhere that is not the editor. Here it is one function both clients reach
 * through {@code POST /notebook/pages/{id}/append}, instead of two converters that would drift.
 *
 * <p>Deliberately small: headings, bullet and numbered lists, fenced code, paragraphs, and
 * bold, italic and inline code inside text. That is what the prompts ask the model to write.
 * Anything else arrives as a paragraph with its characters intact, which the person can tidy
 * by hand; nothing is dropped.
 *
 * <p>The blocks carry no ids. BlockNote assigns them when it loads the document, and an id made
 * up here could collide with one already on the page.
 *
 * <p>Citation markers like {@code [2]} are removed. They point into a chat's list of sources,
 * and on the page there is no list for them to point at.
 */
public final class MarkdownBlocks {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)$");
    private static final Pattern FENCE = Pattern.compile("^```\\s*([\\w+-]*)\\s*$");
    private static final Pattern CITATION = Pattern.compile("\\s?\\[\\d+(?:\\s*,\\s*\\d+)*\\]");
    /** Bold, then inline code, then italic: the order the alternation tries them in. */
    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])|(?<![_\\w])_(?!\\s)(.+?)(?<!\\s)_(?![_\\w])");

    private MarkdownBlocks() {}

    public static ArrayNode toBlocks(String markdown) {
        ArrayNode blocks = MAPPER.createArrayNode();
        if (markdown == null || markdown.isBlank()) {
            return blocks;
        }
        String[] lines = CITATION.matcher(markdown).replaceAll("").replace("\r\n", "\n").split("\n", -1);
        List<String> paragraph = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            Matcher fence = FENCE.matcher(line.strip());
            if (fence.matches()) {
                flush(paragraph, blocks);
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].strip().startsWith("```")) {
                    if (!code.isEmpty()) code.append('\n');
                    code.append(lines[i]);
                    i++;
                }
                i++; // the closing fence, if there was one
                ObjectNode block = block("codeBlock");
                String language = fence.group(1);
                block.putObject("props").put("language", language.isEmpty() ? "text" : language.toLowerCase());
                ArrayNode content = block.putArray("content");
                content.add(text(code.toString(), false, false, false));
                blocks.add(block);
                continue;
            }
            if (line.isBlank()) {
                flush(paragraph, blocks);
                i++;
                continue;
            }
            Matcher heading = HEADING.matcher(line.strip());
            Matcher bullet = BULLET.matcher(line);
            Matcher numbered = NUMBERED.matcher(line);
            if (heading.matches()) {
                flush(paragraph, blocks);
                ObjectNode block = block("heading");
                block.putObject("props").put("level", heading.group(1).length());
                block.set("content", inline(heading.group(2)));
                blocks.add(block);
            } else if (bullet.matches()) {
                flush(paragraph, blocks);
                ObjectNode block = block("bulletListItem");
                block.set("content", inline(bullet.group(1)));
                blocks.add(block);
            } else if (numbered.matches()) {
                flush(paragraph, blocks);
                ObjectNode block = block("numberedListItem");
                block.set("content", inline(numbered.group(1)));
                blocks.add(block);
            } else {
                paragraph.add(line.strip());
            }
            i++;
        }
        flush(paragraph, blocks);
        return blocks;
    }

    /**
     * {@code existing} with {@code markdown}'s blocks added at the end, as a JSON string.
     *
     * <p>An existing document that does not parse is replaced rather than appended to: there is
     * nothing in it the editor could show, so keeping it would only keep the page broken.
     */
    public static String append(String existingJson, String markdown) {
        ArrayNode document = MAPPER.createArrayNode();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                JsonNode parsed = MAPPER.readTree(existingJson);
                if (parsed.isArray()) {
                    document = (ArrayNode) parsed;
                }
            } catch (RuntimeException unreadable) {
                // See the method comment.
            }
        }
        document.addAll(toBlocks(markdown));
        return MAPPER.writeValueAsString(document);
    }

    /**
     * The block type the editors register for "Roadmap board". Server-made pages (the AI draft)
     * put one in their document so the board shows where a person would have put it.
     */
    public static final String BOARD_BLOCK_TYPE = "roadmapBoard";

    /** A new page's document: an optional opening paragraph, then the board block. */
    public static String boardDocument(String intro) {
        ArrayNode document = MAPPER.createArrayNode();
        if (intro != null && !intro.isBlank()) {
            ObjectNode paragraph = block("paragraph");
            paragraph.set("content", inline(intro.strip()));
            document.add(paragraph);
        }
        document.add(block(BOARD_BLOCK_TYPE));
        return MAPPER.writeValueAsString(document);
    }

    /**
     * {@code existing} with a board block at the end, or unchanged when it already has one. A
     * board whose page document has no board block is never drawn, so whatever adds nodes to a
     * page from outside the board (the assistant) makes sure there is somewhere to draw them.
     */
    public static String withBoardBlock(String existingJson) {
        ArrayNode document = MAPPER.createArrayNode();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                JsonNode parsed = MAPPER.readTree(existingJson);
                if (parsed.isArray()) {
                    document = (ArrayNode) parsed;
                }
            } catch (RuntimeException unreadable) {
                // Same as append: a document that does not parse has nothing worth keeping.
            }
        }
        for (JsonNode block : document) {
            if (BOARD_BLOCK_TYPE.equals(block.path("type").asString())) return existingJson;
        }
        document.add(block(BOARD_BLOCK_TYPE));
        return MAPPER.writeValueAsString(document);
    }

    private static void flush(List<String> paragraph, ArrayNode blocks) {
        if (paragraph.isEmpty()) return;
        ObjectNode block = block("paragraph");
        block.set("content", inline(String.join(" ", paragraph)));
        blocks.add(block);
        paragraph.clear();
    }

    private static ObjectNode block(String type) {
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", type);
        return block;
    }

    static ArrayNode inline(String text) {
        ArrayNode parts = MAPPER.createArrayNode();
        Matcher m = INLINE.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                parts.add(text(text.substring(last, m.start()), false, false, false));
            }
            if (m.group(1) != null) parts.add(text(m.group(1), true, false, false));
            else if (m.group(2) != null) parts.add(text(m.group(2), true, false, false));
            else if (m.group(3) != null) parts.add(text(m.group(3), false, false, true));
            else if (m.group(4) != null) parts.add(text(m.group(4), false, true, false));
            else parts.add(text(m.group(5), false, true, false));
            last = m.end();
        }
        if (last < text.length()) {
            parts.add(text(text.substring(last), false, false, false));
        }
        return parts;
    }

    private static ObjectNode text(String value, boolean bold, boolean italic, boolean code) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "text");
        node.put("text", value);
        ObjectNode styles = node.putObject("styles");
        if (bold) styles.put("bold", true);
        if (italic) styles.put("italic", true);
        if (code) styles.put("code", true);
        return node;
    }
}

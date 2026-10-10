package beyou.beyouapp.backend.domain.aiAgent.notebook;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.aiAgent.AiIconCatalog;
import beyou.beyouapp.backend.domain.notebook.MarkdownBlocks;
import beyou.beyouapp.backend.domain.notebook.NotebookOwnership;
import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.ai.NotebookAiService;
import beyou.beyouapp.backend.domain.notebook.ai.NotebookTransactions;
import beyou.beyouapp.backend.domain.notebook.ai.dto.AiCardsRequestDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.CardDTO;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardService;
import beyou.beyouapp.backend.domain.notebook.board.NotebookNodeKind;
import beyou.beyouapp.backend.domain.notebook.board.dto.BoardEdgeDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.BoardNodeDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.BoardResponseDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateEdgeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateNodeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.UpdateNodeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.AppendRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.SetStatusRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.StatusChangeResponseDTO;
import beyou.beyouapp.backend.domain.notebook.dto.StatusChoice;
import beyou.beyouapp.backend.domain.notebook.dto.UpdatePageRequestDTO;
import beyou.beyouapp.backend.security.ratelimit.NotebookAiQuota;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * What the assistant can do to a roadmap board and the pages on it, addressed the way a person
 * talks about them: "the board on Fundamentos", "the node Redes", "put Redes after Sistemas",
 * "make cards for Redes".
 *
 * <p>Every change goes through the same notebook services as the board on screen, so ownership,
 * the link rules and the status and XP that follow a change are the ones a click gets. What lives
 * here is only the translation from names to rows, and the refusals that keep a model from
 * guessing: a name that matches nothing lists what is there, and a name that matches two things
 * asks which one.
 *
 * <p>Each write is one transaction. A tool call that adds a node and then fails to link it leaves
 * nothing behind, so the model's retry does not find a half-done change to trip over.
 */
@Service
@RequiredArgsConstructor
public class StudyBoardEditor {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int TITLE_MAX = 255;
    private static final int NOTES_MAX = 20_000;
    private static final int CARDS_MAX = 12;
    private static final int FOCUS_MAX = 8_000;

    private final NotebookPageService pageService;
    private final NotebookBoardService boardService;
    private final NotebookPageRepository pageRepository;
    private final NotebookOwnership ownership;
    private final NotebookAiService aiService;
    private final NotebookAiQuota aiQuota;
    private final NotebookTransactions tx;

    // ----------------------------------------------------------------- reads

    /** The board as the model needs it: page nodes in path order, links by title, sections. */
    @Transactional(readOnly = true)
    public Map<String, Object> read(User user, String board) {
        NotebookPage page = boardPage(user, board);
        BoardResponseDTO content = boardService.board(user, page.getId());
        Map<UUID, String> titles = content.nodes().stream()
                .collect(Collectors.toMap(BoardNodeDTO::id, BoardNodeDTO::title));

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (BoardNodeDTO node : NotebookBoardService.pathOrder(content)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", node.id());
            row.put("title", node.title());
            row.put("status", node.status());
            if (node.hasBoard() && node.progress() != null) {
                row.put("progress", node.progress().done() + " of " + node.progress().total() + " done");
            }
            if (node.linked()) row.put("linkedFromTopic", node.homeTopicTitle());
            nodes.add(row);
        }
        List<Map<String, Object>> links = content.edges().stream()
                .map(edge -> Map.<String, Object>of("from", titles.getOrDefault(edge.source(), "?"),
                        "to", titles.getOrDefault(edge.target(), "?")))
                .toList();
        List<Map<String, Object>> sections = content.nodes().stream()
                .filter(n -> n.kind() == NotebookNodeKind.SECTION)
                .map(n -> Map.<String, Object>of("id", n.id(), "label", n.title()))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("board", page.getTitle());
        result.put("boardPageId", page.getId());
        result.put("status", page.getStatus());
        result.put("nodes", nodes);
        result.put("links", links);
        if (!sections.isEmpty()) result.put("sections", sections);
        return result;
    }

    // ---------------------------------------------------------------- writes

    /**
     * A new node, which is a new page under the board's page, on the next free grid cell. With
     * {@code after}, also a link from that node to the new one.
     */
    @Transactional
    public Map<String, String> addNode(User user, String board, String title, String after) {
        NotebookPage page = boardPage(user, board);
        String name = required(title, "nodeTitle");
        BoardNodeDTO previous = isBlank(after) ? null : pageNode(user, page, after);

        // No coordinates: the next free cell. The service links it after the previous node and
        // makes sure the page shows its board, as it does for the phone.
        boardService.addNode(user, page.getId(), new CreateNodeRequestDTO(
                NotebookNodeKind.PAGE, name, null, null, null, null, null, null, previous == null ? null : previous.id()));
        return success("Added \"" + name + "\" to the board of \"" + page.getTitle() + "\""
                + (previous == null ? "" : ", after \"" + previous.title() + "\""));
    }

    /**
     * Renames a node or changes its icon. A page node's title is its page's title, so this renames
     * the page everywhere it shows, including its home topic when the node is a link.
     */
    @Transactional
    public Map<String, String> editNode(User user, String board, String node, String newTitle, String icon) {
        NotebookPage page = boardPage(user, board);
        BoardNodeDTO target = anyNode(user, page, node);
        String title = isBlank(newTitle) ? null : checkedTitle(newTitle, "newTitle");
        String iconId = iconOrNull(icon);
        if (title == null && iconId == null) {
            throw new IllegalArgumentException("Send newTitle, icon, or both. Nothing would change otherwise");
        }

        if (target.kind() == NotebookNodeKind.SECTION) {
            if (iconId != null) throw new IllegalArgumentException("A section has no icon. Only its label can change");
            boardService.updateNode(user, target.id(), new UpdateNodeRequestDTO(null, null, null, null, title));
            return success("Renamed the section \"" + target.title() + "\" to \"" + title + "\"");
        }
        pageService.update(user, target.pageId(), new UpdatePageRequestDTO(title, iconId, null));
        List<String> changes = new ArrayList<>();
        if (title != null) changes.add("renamed to \"" + title + "\"");
        if (iconId != null) changes.add(iconId.isEmpty() ? "icon removed" : "icon set to " + iconId);
        return success("\"" + target.title() + "\": " + String.join(", ", changes)
                + (target.linked() ? " (a linked page, so its home topic \"" + target.homeTopicTitle()
                        + "\" shows the change too)" : ""));
    }

    /**
     * Sets a node's status, or the board page's own status when {@code node} is blank. AUTO hands
     * the status back to the board, for a page whose status follows its own nodes.
     */
    @Transactional
    public Map<String, Object> setStatus(User user, String board, String node, String status) {
        NotebookPage page = boardPage(user, board);
        StatusChoice choice = statusChoice(status);
        UUID pageId;
        String title;
        if (isBlank(node)) {
            pageId = page.getId();
            title = page.getTitle();
        } else {
            BoardNodeDTO target = pageNode(user, page, node);
            pageId = target.pageId();
            title = target.title();
        }
        StatusChangeResponseDTO change = pageService.setStatus(user, pageId, new SetStatusRequestDTO(choice));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", "\"" + title + "\" is now " + change.status()
                + (change.statusManual() ? "" : " (following its board)"));
        result.put("xpEarned", change.xpEarned());
        return result;
    }

    /** "Study {@code from} before {@code to}". */
    @Transactional
    public Map<String, String> connect(User user, String board, String from, String to) {
        NotebookPage page = boardPage(user, board);
        BoardNodeDTO source = pageNode(user, page, from);
        BoardNodeDTO target = pageNode(user, page, to);
        boardService.addEdge(user, page.getId(), new CreateEdgeRequestDTO(source.id(), target.id()));
        return success("Linked \"" + source.title() + "\" → \"" + target.title() + "\"");
    }

    @Transactional
    public Map<String, String> disconnect(User user, String board, String from, String to) {
        NotebookPage page = boardPage(user, board);
        BoardResponseDTO content = boardService.board(user, page.getId());
        BoardNodeDTO source = pageNode(content, from);
        BoardNodeDTO target = pageNode(content, to);
        BoardEdgeDTO edge = content.edges().stream()
                .filter(e -> e.source().equals(source.id()) && e.target().equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(content.edges().stream()
                        .anyMatch(e -> e.source().equals(target.id()) && e.target().equals(source.id()))
                        ? "The link goes the other way: \"" + target.title() + "\" → \"" + source.title()
                                + "\". Swap from and to"
                        : "\"" + source.title() + "\" and \"" + target.title() + "\" are not linked"));
        boardService.deleteEdge(user, edge.id());
        return success("Removed the link \"" + source.title() + "\" → \"" + target.title() + "\"");
    }

    /**
     * Takes a node off the board. The page stays in the tree with its notes unless
     * {@code deletePage}; a linked page is never deleted from here, only unlinked, because it
     * lives in another topic.
     */
    @Transactional
    public Map<String, String> remove(User user, String board, String node, boolean deletePage) {
        NotebookPage page = boardPage(user, board);
        BoardNodeDTO target = anyNode(user, page, node);
        boardService.deleteNode(user, target.id(), deletePage);
        if (target.kind() == NotebookNodeKind.SECTION) {
            return success("Removed the section \"" + target.title() + "\". The nodes inside it stay");
        }
        if (target.linked()) {
            return success("Unlinked \"" + target.title() + "\" from this board. The page stays in its topic \""
                    + target.homeTopicTitle() + "\"");
        }
        return success(deletePage
                ? "Deleted \"" + target.title() + "\", its notes and every page under it"
                : "Took \"" + target.title() + "\" off the board. The page and its notes stay in the tree");
    }

    /**
     * Makes the board one path in the order given, every page node on the grid, links replaced by
     * the chain. See {@link NotebookBoardService#restructure}.
     */
    @Transactional
    public Map<String, String> reorder(User user, String board, List<String> order) {
        NotebookPage page = boardPage(user, board);
        BoardResponseDTO content = boardService.board(user, page.getId());
        if (order == null || order.isEmpty()) {
            throw new IllegalArgumentException("Send the node titles in the new order, every page node of the board once");
        }
        List<BoardNodeDTO> nodes = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (String ref : order) {
            BoardNodeDTO node = pageNode(content, ref);
            if (!seen.add(node.id())) {
                throw new IllegalArgumentException("\"" + node.title() + "\" is in the order twice");
            }
            nodes.add(node);
        }
        List<String> missing = content.nodes().stream()
                .filter(n -> n.kind() == NotebookNodeKind.PAGE && !seen.contains(n.id()))
                .map(BoardNodeDTO::title)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("The order leaves out " + quoted(missing)
                    + ". Every page node of the board goes in the order. To take one off, use removeStudyNode first");
        }
        boardService.restructure(user, page.getId(), nodes.stream().map(BoardNodeDTO::id).toList());
        return success("The board of \"" + page.getTitle() + "\" is now one path: "
                + nodes.stream().map(BoardNodeDTO::title).collect(Collectors.joining(" → ")));
    }

    /** Markdown at the end of a node's page, or of the board page itself when {@code node} is blank. */
    @Transactional
    public Map<String, String> appendNotes(User user, String board, String node, String markdown) {
        NotebookPage page = boardPage(user, board);
        String notes = required(markdown, "markdown");
        if (notes.length() > NOTES_MAX) {
            throw new IllegalArgumentException("Notes are at most " + NOTES_MAX + " characters at a time");
        }
        UUID pageId = page.getId();
        String title = page.getTitle();
        if (!isBlank(node)) {
            BoardNodeDTO target = pageNode(user, page, node);
            pageId = target.pageId();
            title = target.title();
        }
        pageService.append(user, pageId, new AppendRequestDTO(notes));
        return success("Added the notes at the end of \"" + title + "\"");
    }

    /**
     * Flashcards drafted by the study AI from a node's page, or the board page when {@code node} is
     * blank, the same call as "Draft with AI" on the cards block. It reads the page's notes and its
     * sources, or only {@code focus} when given, and saves what comes back. The page gets a cards
     * block if it had none, so the cards show where the person reads. It spends the notebook-ai
     * quota like the button does.
     */
    public Map<String, Object> generateCards(User user, String board, String node, Integer count, String focus) {
        // Not @Transactional, unlike the other tools here: the cards call reaches the model, and
        // a transaction open around it would hold a connection for as long as the model takes.
        // The lookup gets a short one of its own; NotebookAiService.cards and ensureBlock open theirs.
        record Target(UUID pageId, String title) {
        }
        Target target = tx.read(() -> {
            NotebookPage page = boardPage(user, board);
            if (isBlank(node)) {
                return new Target(page.getId(), page.getTitle());
            }
            BoardNodeDTO found = pageNode(user, page, node);
            return new Target(found.pageId(), found.title());
        });
        UUID pageId = target.pageId();
        String title = target.title();
        if (count != null && (count < 1 || count > CARDS_MAX)) {
            throw new IllegalArgumentException("count is between 1 and " + CARDS_MAX);
        }
        String text = isBlank(focus) ? null : focus.strip();
        if (text != null && text.length() > FOCUS_MAX) {
            throw new IllegalArgumentException("focus is at most " + FOCUS_MAX + " characters");
        }
        aiQuota.spend(user.getId());
        List<CardDTO> cards = aiService.cards(user, pageId, new AiCardsRequestDTO(text, count));
        pageService.ensureBlock(user, pageId, MarkdownBlocks.CARDS_BLOCK_TYPE);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", "Added " + cards.size() + " flashcards to \"" + title + "\"");
        result.put("questions", cards.stream().map(CardDTO::front).toList());
        return result;
    }

    // ------------------------------------------------------------- resolving

    /**
     * The page whose board is meant. Takes a page id, the route the person is on
     * ("/notebook/{id}", "/notebook/{id}/board"), or the page's exact title, in any case.
     */
    NotebookPage boardPage(User user, String board) {
        if (isBlank(board)) {
            throw new IllegalArgumentException("Say which board: the page id from the route the user is on "
                    + "(/notebook/<id>), or the exact title of the topic or page that holds it");
        }
        Matcher id = UUID_PATTERN.matcher(board);
        if (id.find()) {
            return ownership.page(user.getId(), UUID.fromString(id.group()));
        }
        String title = board.strip();
        List<NotebookPage> pages = pageRepository.findByUserIdAndTitleIgnoreCase(user.getId(), title);
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("No notebook page is titled \"" + title
                    + "\". listStudyTopics shows the topics; ask the user which page they mean");
        }
        if (pages.size() > 1) {
            Map<UUID, String> topics = pageRepository.findByIdIn(pages.stream().map(NotebookPage::rootId).toList())
                    .stream().collect(Collectors.toMap(NotebookPage::getId, NotebookPage::getTitle));
            throw new IllegalArgumentException("Several pages are titled \"" + title + "\", in the topics "
                    + quoted(pages.stream().map(p -> topics.getOrDefault(p.rootId(), "?")).distinct().toList())
                    + ". Ask which one, or use the page id");
        }
        return pages.get(0);
    }

    private BoardNodeDTO pageNode(User user, NotebookPage page, String ref) {
        return pageNode(boardService.board(user, page.getId()), ref);
    }

    private BoardNodeDTO anyNode(User user, NotebookPage page, String ref) {
        return node(boardService.board(user, page.getId()), ref, false);
    }

    private static BoardNodeDTO pageNode(BoardResponseDTO board, String ref) {
        return node(board, ref, true);
    }

    /**
     * A node by its id, its page's id, or its title. Titles are compared in any case, because the
     * person types them from memory and the model copies what the person typed.
     */
    private static BoardNodeDTO node(BoardResponseDTO board, String ref, boolean pagesOnly) {
        if (isBlank(ref)) {
            throw new IllegalArgumentException("Say which node: its title as getStudyBoard lists it, or its id");
        }
        String wanted = ref.strip();
        List<BoardNodeDTO> candidates = board.nodes().stream()
                .filter(n -> !pagesOnly || n.kind() == NotebookNodeKind.PAGE)
                .toList();
        List<BoardNodeDTO> byId = candidates.stream()
                .filter(n -> n.id().toString().equalsIgnoreCase(wanted)
                        || (n.pageId() != null && n.pageId().toString().equalsIgnoreCase(wanted)))
                .toList();
        if (!byId.isEmpty()) return byId.get(0);

        List<BoardNodeDTO> byTitle = candidates.stream()
                .filter(n -> n.title().equalsIgnoreCase(wanted))
                .toList();
        if (byTitle.size() == 1) return byTitle.get(0);
        if (byTitle.size() > 1) {
            throw new IllegalArgumentException("Several nodes on this board are titled \"" + wanted
                    + "\". Use the node id from getStudyBoard");
        }
        if (pagesOnly && board.nodes().stream()
                .anyMatch(n -> n.kind() == NotebookNodeKind.SECTION && n.title().equalsIgnoreCase(wanted))) {
            throw new IllegalArgumentException("\"" + wanted + "\" is a section, a label around nodes. "
                    + "Links, statuses and notes go on page nodes");
        }
        throw new IllegalArgumentException("No node on this board is called \"" + wanted + "\". The board has "
                + (candidates.isEmpty() ? "no nodes yet" : quoted(candidates.stream().map(BoardNodeDTO::title).toList())));
    }

    // --------------------------------------------------------------- helpers

    private static StatusChoice statusChoice(String status) {
        if (!isBlank(status)) {
            String wanted = status.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            for (StatusChoice choice : StatusChoice.values()) {
                if (choice.name().equals(wanted)) return choice;
            }
        }
        throw new IllegalArgumentException("status is one of TO_STUDY, STUDYING, DONE or AUTO (follow the board)");
    }

    /** Null for "leave the icon alone", "" for "remove it", otherwise a catalog id. */
    private static String iconOrNull(String icon) {
        if (isBlank(icon)) return null;
        String wanted = icon.strip();
        if (wanted.equalsIgnoreCase("none")) return "";
        if (!AiIconCatalog.isValid(wanted)) {
            throw new IllegalArgumentException("\"" + wanted + "\" is not in the icon catalog. Use an id from it, "
                    + "or \"none\" to remove the icon");
        }
        return wanted;
    }

    private static String checkedTitle(String value, String field) {
        String title = value.strip();
        if (title.length() > TITLE_MAX) {
            throw new IllegalArgumentException(field + " is at most " + TITLE_MAX + " characters");
        }
        return title;
    }

    private static String required(String value, String field) {
        if (isBlank(value)) throw new IllegalArgumentException(field + " is required");
        return field.equals("markdown") ? value.strip() : checkedTitle(value, field);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String quoted(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(", "));
    }

    private static Map<String, String> success(String message) {
        return Map.of("success", message);
    }
}

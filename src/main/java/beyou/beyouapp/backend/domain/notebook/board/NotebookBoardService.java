package beyou.beyouapp.backend.domain.notebook.board;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.notebook.MarkdownBlocks;
import beyou.beyouapp.backend.domain.notebook.NotebookOwnership;
import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.NotebookProgressService;
import beyou.beyouapp.backend.domain.notebook.NotebookStatus;
import beyou.beyouapp.backend.domain.notebook.ProgressGraph;
import beyou.beyouapp.backend.domain.notebook.board.dto.BoardChangeResponseDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.BoardEdgeDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.BoardNodeDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.BoardResponseDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateEdgeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateNodeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.LayoutRequestDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.NodePositionDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.UpdateNodeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.PageStatusDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * A page's roadmap board: its nodes, its edges, and where they sit.
 *
 * <p>Each page has at most one board, and the board is the set of rows keyed by the page id.
 * The editor's "Roadmap board" block only decides where on the page it is drawn; removing the
 * block from the document hides the board without deleting a node.
 *
 * <p>A new node is a new child page. A linked node shows a page that lives elsewhere, and two
 * links are refused: a page twice on one board, and a page that would end up on its own board
 * through any chain of boards ({@link ProgressGraph#reaches}). Every change ends in
 * {@link NotebookProgressService#boardChanged}, because what is on a board decides the board
 * page's status.
 */
@Service
@RequiredArgsConstructor
public class NotebookBoardService {

    /**
     * The board grid for nodes the server lays out (drafts, chains): three to a row, read left to
     * right and top to bottom, from x 40. The web board's "Tidy up" lays nodes out on the same
     * grid (boardLayout.ts in the web app), so tidying a fresh draft moves nothing. Change one
     * and change the other.
     */
    static final double COLUMN_STEP = 240;
    static final double ROW_STEP = 140;
    static final int NODES_PER_ROW = 3;
    /** The size the web draws a page node at (NODE_WIDTH and NODE_HEIGHT in boardLayout.ts). */
    static final double NODE_WIDTH = 176;
    static final double NODE_HEIGHT = 64;

    /** A spot on the board grid. */
    public record GridCell(double x, double y) {
    }

    /** The grid cell at {@code index}, in reading order, from x 40 and y 0. */
    public static GridCell gridCell(int index) {
        return new GridCell(40 + (index % NODES_PER_ROW) * COLUMN_STEP, (index / NODES_PER_ROW) * ROW_STEP);
    }

    private final NotebookBoardNodeRepository nodeRepository;
    private final NotebookBoardEdgeRepository edgeRepository;
    private final NotebookPageRepository pageRepository;
    private final NotebookOwnership ownership;
    private final NotebookPageService pageService;
    private final NotebookProgressService progressService;

    @Transactional(readOnly = true)
    public BoardResponseDTO board(User user, UUID pageId) {
        ownership.page(user.getId(), pageId);
        ProgressGraph graph = progressService.graphFor(user.getId());
        List<NotebookBoardNode> nodes = nodeRepository.findByBoardPageIdOrderByCreatedAtAsc(pageId);
        Map<UUID, String> topicTitles = topicTitles(graph, nodes);
        List<BoardNodeDTO> nodeDtos = nodes.stream().map(n -> toDto(graph, n, pageId, topicTitles)).toList();
        List<BoardEdgeDTO> edges = edgeRepository.findByBoardPageId(pageId).stream()
                .map(e -> new BoardEdgeDTO(e.getId(), e.getSourceNodeId(), e.getTargetNodeId()))
                .toList();
        return new BoardResponseDTO(pageId, nodeDtos, edges);
    }

    /**
     * A node on the board. Without coordinates it goes on the next free grid cell; with
     * {@code after} it is linked from that node, so it lands after it on the path. Either way the
     * page's document gets its board block if it has none: a board grown from outside the page
     * (the phone, the assistant) would otherwise have nodes nobody can see on the web.
     */
    @Transactional
    public BoardChangeResponseDTO addNode(User user, UUID boardPageId, CreateNodeRequestDTO request) {
        NotebookPage board = ownership.page(user.getId(), boardPageId);
        NotebookNodeKind kind = request.kind() == null ? NotebookNodeKind.PAGE : request.kind();
        if ((request.x() == null) != (request.y() == null)) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "x and y go together, or neither is sent");
        }
        if (request.after() != null && kind != NotebookNodeKind.PAGE) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "Only a page node can follow another");
        }
        GridCell cell = request.x() == null ? nextFreeCell(boardPageId) : new GridCell(request.x(), request.y());
        NotebookBoardNode node = new NotebookBoardNode();
        node.setUser(board.getUser());
        node.setBoardPageId(boardPageId);
        node.setKind(kind);
        node.setX(cell.x());
        node.setY(cell.y());
        node.setCreatedAt(Instant.now());

        if (kind == NotebookNodeKind.SECTION) {
            if (request.label() == null || request.label().isBlank()) {
                throw new BusinessException(ErrorKey.INVALID_REQUEST, "A section needs a label");
            }
            node.setLabel(request.label().strip());
            node.setWidth(request.width() == null ? 420.0 : request.width());
            node.setHeight(request.height() == null ? 260.0 : request.height());
        } else {
            boolean hasTitle = request.title() != null && !request.title().isBlank();
            if (hasTitle == (request.linkPageId() != null)) {
                throw new BusinessException(ErrorKey.INVALID_REQUEST,
                        "A page node needs either a title or a page to link, not both");
            }
            UUID pageId = hasTitle
                    ? pageService.newChild(board, request.title(), null).getId()
                    : linkable(user, board, request.linkPageId()).getId();
            node.setPageId(pageId);
        }
        nodeRepository.save(node);
        if (request.after() != null) {
            addEdge(user, boardPageId, new CreateEdgeRequestDTO(request.after(), node.getId()));
        }
        NotebookProgressService.Outcome outcome = progressService.boardChanged(user.getId(), boardPageId);

        ProgressGraph graph = progressService.graphFor(user.getId());
        BoardNodeDTO dto = toDto(graph, node, boardPageId, topicTitles(graph, List.of(node)));
        // Last: the document's compare-and-set clears the persistence context.
        pageService.ensureBlock(user, boardPageId, MarkdownBlocks.BOARD_BLOCK_TYPE);
        return new BoardChangeResponseDTO(dto, outcome.changed(), outcome.refreshUi());
    }

    /**
     * Lays out a row of nodes on a board, chained left to right with edges, wrapping every three.
     * Used by the AI draft so a model never picks coordinates.
     *
     * <p>Returns one entry per item, in order: the node made for it, or null when the item was a
     * link to a page already on this board. Callers pair items with nodes by index.
     */
    @Transactional
    public List<NotebookBoardNode> addChain(NotebookPage board, List<ChainItem> items) {
        List<NotebookBoardNode> existing = nodeRepository.findByBoardPageIdOrderByCreatedAtAsc(board.getId());
        double startY = existing.stream().mapToDouble(NotebookBoardNode::getY).max().orElse(-ROW_STEP) + ROW_STEP;
        List<NotebookBoardNode> result = new ArrayList<>();
        Instant now = Instant.now();
        int placed = 0;
        for (ChainItem item : items) {
            if (item.linkPageId() != null
                    && (nodeRepository.findByBoardPageIdAndPageId(board.getId(), item.linkPageId()).isPresent()
                        || result.stream().anyMatch(n -> n != null && item.linkPageId().equals(n.getPageId())))) {
                result.add(null);
                continue;
            }
            UUID pageId = item.linkPageId() != null
                    ? item.linkPageId()
                    : pageService.newChild(board, item.title(), null).getId();
            NotebookBoardNode node = new NotebookBoardNode();
            node.setUser(board.getUser());
            node.setBoardPageId(board.getId());
            node.setPageId(pageId);
            node.setKind(NotebookNodeKind.PAGE);
            node.setX(40 + (placed % NODES_PER_ROW) * COLUMN_STEP);
            node.setY(startY + (placed / NODES_PER_ROW) * ROW_STEP);
            // One instant apart each: the board lists nodes by creation time, and a chain
            // created in one go must keep the order it was drafted in.
            node.setCreatedAt(now.plusNanos(1000L * placed));
            result.add(nodeRepository.save(node));
            placed++;
        }
        NotebookBoardNode previous = null;
        for (NotebookBoardNode node : result) {
            if (node == null) continue;
            if (previous != null) {
                NotebookBoardEdge edge = new NotebookBoardEdge();
                edge.setUser(board.getUser());
                edge.setBoardPageId(board.getId());
                edge.setSourceNodeId(previous.getId());
                edge.setTargetNodeId(node.getId());
                edgeRepository.save(edge);
            }
            previous = node;
        }
        return result;
    }

    /** One entry of {@link #addChain}: a new page by title, or an existing page to link. */
    public record ChainItem(String title, UUID linkPageId) {
    }

    /**
     * The page nodes in path order: every node after the nodes that point to it, and where the
     * edges leave a choice, the node that comes first where the person put it (row, then left to
     * right). A loop of edges is broken at its first node in that order, so every node still gets
     * a place. The web's "Tidy up" reads a board the same way (pathOrder in boardLayout.ts).
     */
    public static List<BoardNodeDTO> pathOrder(BoardResponseDTO board) {
        List<BoardNodeDTO> pages = board.nodes().stream()
                .filter(n -> n.kind() == NotebookNodeKind.PAGE)
                .sorted(Comparator.comparingLong((BoardNodeDTO n) -> Math.round(n.y() / ROW_STEP))
                        .thenComparingDouble(BoardNodeDTO::x))
                .toList();
        Set<UUID> ids = pages.stream().map(BoardNodeDTO::id).collect(Collectors.toSet());
        Map<UUID, Integer> incoming = new HashMap<>();
        Map<UUID, List<UUID>> targets = new HashMap<>();
        for (BoardEdgeDTO edge : board.edges()) {
            if (!ids.contains(edge.source()) || !ids.contains(edge.target()) || edge.source().equals(edge.target())) {
                continue;
            }
            incoming.merge(edge.target(), 1, Integer::sum);
            targets.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.target());
        }
        List<BoardNodeDTO> ordered = new ArrayList<>();
        Set<UUID> placed = new HashSet<>();
        while (ordered.size() < pages.size()) {
            BoardNodeDTO next = pages.stream()
                    .filter(n -> !placed.contains(n.id()) && incoming.getOrDefault(n.id(), 0) == 0)
                    .findFirst()
                    .orElseGet(() -> pages.stream().filter(n -> !placed.contains(n.id())).findFirst().orElseThrow());
            placed.add(next.id());
            ordered.add(next);
            for (UUID target : targets.getOrDefault(next.id(), List.of())) {
                incoming.merge(target, -1, Integer::sum);
            }
        }
        return ordered;
    }

    /**
     * Where a new node goes: the next grid cell after the ones already used, skipping any cell a
     * page node has been dragged onto. The web picks the same cell for a node added by hand
     * (nextNodePosition in boardLayout.ts), so a node never lands on top of another.
     */
    @Transactional(readOnly = true)
    public GridCell nextFreeCell(UUID boardPageId) {
        List<NotebookBoardNode> pages = nodeRepository.findByBoardPageIdOrderByCreatedAtAsc(boardPageId).stream()
                .filter(n -> n.getKind() == NotebookNodeKind.PAGE)
                .toList();
        int index = pages.size();
        while (taken(pages, gridCell(index))) index++;
        return gridCell(index);
    }

    private static boolean taken(List<NotebookBoardNode> pages, GridCell cell) {
        return pages.stream().anyMatch(n ->
                Math.abs(n.getX() - cell.x()) < NODE_WIDTH && Math.abs(n.getY() - cell.y()) < NODE_HEIGHT);
    }

    /**
     * Makes the board one path: the page nodes in the order given, one after the other, on the
     * grid. Every edge on the board is replaced by the chain, so a branch the person drew is gone
     * afterwards; that is what the order means. Sections stay where they are, as with "Tidy up".
     *
     * <p>{@code order} must name every page node on the board exactly once. A partial order would
     * leave the nodes it skipped somewhere on the grid with no edge to them, which reads as a
     * mistake on the board rather than a choice.
     */
    @Transactional
    public void restructure(User user, UUID boardPageId, List<UUID> order) {
        NotebookPage board = ownership.page(user.getId(), boardPageId);
        Map<UUID, NotebookBoardNode> pages = nodeRepository.findByBoardPageIdOrderByCreatedAtAsc(boardPageId).stream()
                .filter(n -> n.getKind() == NotebookNodeKind.PAGE)
                .collect(Collectors.toMap(NotebookBoardNode::getId, n -> n));
        if (order.size() != new HashSet<>(order).size() || !pages.keySet().equals(new HashSet<>(order))) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST,
                    "The order must name every page node on the board exactly once");
        }
        edgeRepository.deleteAll(edgeRepository.findByBoardPageId(boardPageId));
        edgeRepository.flush();
        NotebookBoardNode previous = null;
        for (int i = 0; i < order.size(); i++) {
            NotebookBoardNode node = pages.get(order.get(i));
            GridCell cell = gridCell(i);
            node.setX(cell.x());
            node.setY(cell.y());
            if (previous != null) {
                NotebookBoardEdge edge = new NotebookBoardEdge();
                edge.setUser(board.getUser());
                edge.setBoardPageId(boardPageId);
                edge.setSourceNodeId(previous.getId());
                edge.setTargetNodeId(node.getId());
                edgeRepository.save(edge);
            }
            previous = node;
        }
    }

    @Transactional
    public BoardChangeResponseDTO updateNode(User user, UUID nodeId, UpdateNodeRequestDTO request) {
        NotebookBoardNode node = ownedNode(user, nodeId);
        if (request.x() != null) node.setX(request.x());
        if (request.y() != null) node.setY(request.y());
        if (node.getKind() == NotebookNodeKind.SECTION) {
            if (request.width() != null) node.setWidth(Math.max(80, request.width()));
            if (request.height() != null) node.setHeight(Math.max(60, request.height()));
            if (request.label() != null && !request.label().isBlank()) node.setLabel(request.label().strip());
        }
        ProgressGraph graph = progressService.graphFor(user.getId());
        BoardNodeDTO dto = toDto(graph, node, node.getBoardPageId(), topicTitles(graph, List.of(node)));
        return new BoardChangeResponseDTO(dto, List.of(), null);
    }

    @Transactional
    public void layout(User user, UUID boardPageId, LayoutRequestDTO request) {
        ownership.page(user.getId(), boardPageId);
        Map<UUID, NotebookBoardNode> byId = nodeRepository.findByBoardPageIdOrderByCreatedAtAsc(boardPageId)
                .stream().collect(Collectors.toMap(NotebookBoardNode::getId, n -> n));
        for (NodePositionDTO position : request.positions()) {
            NotebookBoardNode node = byId.get(position.nodeId());
            if (node == null) continue;
            node.setX(position.x());
            node.setY(position.y());
        }
    }

    /**
     * Removes a node. {@code deletePage} also deletes the page and its subtree, and is only
     * honoured when the page lives under this board's page: a linked page belongs to another
     * topic, and removing it from here must never delete it there.
     */
    @Transactional
    public BoardChangeResponseDTO deleteNode(User user, UUID nodeId, boolean deletePage) {
        NotebookBoardNode node = ownedNode(user, nodeId);
        UUID boardPageId = node.getBoardPageId();
        UUID pageId = node.getPageId();
        nodeRepository.delete(node);
        nodeRepository.flush();
        if (deletePage && pageId != null) {
            NotebookPage page = pageRepository.findById(pageId).orElse(null);
            if (page != null && boardPageId.equals(page.getParentId())) {
                pageService.delete(user, pageId);
            }
        }
        NotebookProgressService.Outcome outcome = progressService.boardChanged(user.getId(), boardPageId);
        return new BoardChangeResponseDTO(null, outcome.changed(), outcome.refreshUi());
    }

    @Transactional
    public BoardEdgeDTO addEdge(User user, UUID boardPageId, CreateEdgeRequestDTO request) {
        ownership.page(user.getId(), boardPageId);
        if (request.source().equals(request.target())) {
            throw new BusinessException(ErrorKey.NOTEBOOK_EDGE_INVALID, "An edge needs two different nodes");
        }
        NotebookBoardNode source = nodeRepository.findById(request.source()).orElse(null);
        NotebookBoardNode target = nodeRepository.findById(request.target()).orElse(null);
        if (source == null || target == null
                || !boardPageId.equals(source.getBoardPageId()) || !boardPageId.equals(target.getBoardPageId())
                || source.getKind() != NotebookNodeKind.PAGE || target.getKind() != NotebookNodeKind.PAGE) {
            throw new BusinessException(ErrorKey.NOTEBOOK_EDGE_INVALID,
                    "Both ends must be page nodes on this board");
        }
        if (edgeRepository.existsBySourceNodeIdAndTargetNodeId(source.getId(), target.getId())) {
            throw new BusinessException(ErrorKey.NOTEBOOK_EDGE_INVALID, "These nodes are already connected");
        }
        NotebookBoardEdge edge = new NotebookBoardEdge();
        edge.setUser(source.getUser());
        edge.setBoardPageId(boardPageId);
        edge.setSourceNodeId(source.getId());
        edge.setTargetNodeId(target.getId());
        edgeRepository.save(edge);
        return new BoardEdgeDTO(edge.getId(), edge.getSourceNodeId(), edge.getTargetNodeId());
    }

    @Transactional
    public void deleteEdge(User user, UUID edgeId) {
        NotebookBoardEdge edge = edgeRepository.findById(edgeId)
                .orElseThrow(() -> new BusinessException(ErrorKey.NOTEBOOK_EDGE_NOT_FOUND, "Edge not found"));
        ownership.page(user.getId(), edge.getBoardPageId());
        edgeRepository.delete(edge);
    }

    // --------------------------------------------------------------- helpers

    /** A page that may be shown on {@code board} through a link. See the class comment. */
    NotebookPage linkable(User user, NotebookPage board, UUID linkPageId) {
        NotebookPage page = ownership.page(user.getId(), linkPageId);
        if (nodeRepository.findByBoardPageIdAndPageId(board.getId(), linkPageId).isPresent()) {
            throw new BusinessException(ErrorKey.NOTEBOOK_NODE_DUPLICATE, "This page is already on the board");
        }
        ProgressGraph graph = progressService.graphFor(user.getId());
        // The board itself, or anything whose boards lead back to it, would become its own child.
        if (graph.reaches(linkPageId, board.getId())) {
            throw new BusinessException(ErrorKey.NOTEBOOK_BOARD_CYCLE,
                    "The page would end up on its own board");
        }
        return page;
    }

    private NotebookBoardNode ownedNode(User user, UUID nodeId) {
        NotebookBoardNode node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new BusinessException(ErrorKey.NOTEBOOK_NODE_NOT_FOUND, "Board node not found"));
        ownership.page(user.getId(), node.getBoardPageId());
        return node;
    }

    private BoardNodeDTO toDto(ProgressGraph graph, NotebookBoardNode node, UUID boardPageId,
            Map<UUID, String> topicTitles) {
        if (node.getKind() == NotebookNodeKind.SECTION) {
            return new BoardNodeDTO(node.getId(), node.getKind(), null, node.getLabel(), null,
                    NotebookStatus.TO_STUDY, null, false, node.getX(), node.getY(),
                    node.getWidth(), node.getHeight(), false, null);
        }
        NotebookPage page = graph.page(node.getPageId());
        boolean linked = page != null && !boardPageId.equals(page.getParentId());
        return new BoardNodeDTO(node.getId(), node.getKind(), node.getPageId(),
                page == null ? "" : page.getTitle(),
                page == null ? null : page.getIcon(),
                page == null ? NotebookStatus.TO_STUDY : page.getStatus(),
                graph.progressOf(node.getPageId()),
                graph.hasBoard(node.getPageId()),
                node.getX(), node.getY(), node.getWidth(), node.getHeight(),
                linked,
                linked ? topicTitles.get(page.rootId()) : null);
    }

    private Map<UUID, String> topicTitles(ProgressGraph graph, List<NotebookBoardNode> nodes) {
        Map<UUID, String> titles = new HashMap<>();
        Set<UUID> wanted = nodes.stream()
                .map(NotebookBoardNode::getPageId)
                .filter(id -> id != null && graph.page(id) != null)
                .map(id -> graph.page(id).rootId())
                .collect(Collectors.toSet());
        for (UUID id : wanted) {
            NotebookPage topic = graph.page(id);
            if (topic != null) titles.put(id, topic.getTitle());
        }
        return titles;
    }
}

package beyou.beyouapp.backend.domain.notebook;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardNode;
import beyou.beyouapp.backend.domain.notebook.board.NotebookNodeKind;
import beyou.beyouapp.backend.domain.notebook.dto.ProgressDTO;

/**
 * Who is on whose board, for one user, in memory.
 *
 * <p>Built from two reads (every page, every PAGE node) and then asked as many questions as a
 * screen needs. The board relation is a graph, not a tree: a linked node puts a page from one
 * topic on another topic's board, so the same page can be reached twice. Every walk here keeps a
 * visited set. NotebookBoardService refuses the links that would close a loop, and the visited
 * set is what keeps a loop that got in some other way from hanging a request.
 *
 * <p>Progress counts leaves. A page whose board has no nodes is a leaf and counts as one; a page
 * with a board counts as the sum of what is on it. So "Software Engineering 11 of 56" means 56
 * things with nothing further broken down under them, wherever they sit.
 */
public final class ProgressGraph {

    private final Map<UUID, NotebookPage> pages;
    /** Board page id → the page ids of its PAGE nodes, in board order. */
    private final Map<UUID, List<UUID>> children;
    /** Page id → the board pages that show it. More than one when the page is linked elsewhere. */
    private final Map<UUID, List<UUID>> boardsShowing;

    private ProgressGraph(Map<UUID, NotebookPage> pages, Map<UUID, List<UUID>> children) {
        this.pages = pages;
        this.children = children;
        this.boardsShowing = new HashMap<>();
        children.forEach((board, kids) -> kids.forEach(
                kid -> boardsShowing.computeIfAbsent(kid, k -> new ArrayList<>()).add(board)));
    }

    public static ProgressGraph of(Collection<NotebookPage> pages, Collection<NotebookBoardNode> nodes) {
        Map<UUID, NotebookPage> byId = new HashMap<>();
        for (NotebookPage page : pages) {
            byId.put(page.getId(), page);
        }
        Map<UUID, List<UUID>> children = new LinkedHashMap<>();
        nodes.stream()
                .filter(n -> n.getKind() == NotebookNodeKind.PAGE && n.getPageId() != null)
                // Left to right, then top to bottom: the order a person reads a board in.
                .sorted(Comparator.comparingDouble(NotebookBoardNode::getX)
                        .thenComparingDouble(NotebookBoardNode::getY))
                .forEach(n -> children.computeIfAbsent(n.getBoardPageId(), k -> new ArrayList<>())
                        .add(n.getPageId()));
        return new ProgressGraph(byId, children);
    }

    public NotebookPage page(UUID id) {
        return pages.get(id);
    }

    /** The pages on this page's board, in reading order. Empty for a leaf. */
    public List<UUID> childrenOf(UUID pageId) {
        return children.getOrDefault(pageId, List.of());
    }

    /** The pages whose boards show this one. */
    public List<UUID> boardsShowing(UUID pageId) {
        return boardsShowing.getOrDefault(pageId, List.of());
    }

    public Collection<NotebookPage> pages() {
        return pages.values();
    }

    public boolean hasBoard(UUID pageId) {
        return !childrenOf(pageId).isEmpty();
    }

    public ProgressDTO progressOf(UUID pageId) {
        int[] counts = new int[2];
        leaves(pageId, new HashSet<>(), counts);
        return new ProgressDTO(counts[0], counts[1]);
    }

    private void leaves(UUID pageId, Set<UUID> visited, int[] counts) {
        if (!visited.add(pageId)) return;
        NotebookPage page = pages.get(pageId);
        if (page == null) return;
        List<UUID> kids = childrenOf(pageId);
        if (kids.isEmpty()) {
            counts[1]++;
            if (page.getStatus() == NotebookStatus.DONE) counts[0]++;
            return;
        }
        for (UUID child : kids) {
            leaves(child, visited, counts);
        }
    }

    /**
     * What the nodes say this page's status is, or null for a leaf (a leaf has nobody to ask).
     *
     * <p>Every node done is done. Anything done or started is studying. Otherwise nothing has
     * begun.
     */
    public NotebookStatus derivedStatus(UUID pageId) {
        List<UUID> kids = childrenOf(pageId);
        if (kids.isEmpty()) return null;
        boolean allDone = true;
        boolean anyStarted = false;
        for (UUID child : kids) {
            NotebookPage page = pages.get(child);
            NotebookStatus status = page == null ? NotebookStatus.TO_STUDY : page.getStatus();
            if (status != NotebookStatus.DONE) allDone = false;
            if (status != NotebookStatus.TO_STUDY) anyStarted = true;
        }
        if (allDone) return NotebookStatus.DONE;
        return anyStarted ? NotebookStatus.STUDYING : NotebookStatus.TO_STUDY;
    }

    /**
     * Whether {@code candidate} can reach {@code target} by walking boards downward. Used to
     * refuse a node that would put a page on its own board, directly or through others.
     */
    public boolean reaches(UUID candidate, UUID target) {
        if (candidate.equals(target)) return true;
        Set<UUID> visited = new HashSet<>();
        List<UUID> stack = new ArrayList<>(List.of(candidate));
        while (!stack.isEmpty()) {
            UUID current = stack.remove(stack.size() - 1);
            if (!visited.add(current)) continue;
            for (UUID child : childrenOf(current)) {
                if (child.equals(target)) return true;
                stack.add(child);
            }
        }
        return false;
    }

    /**
     * The page and everything below it, through both relations: the tree (pages whose parent
     * chain runs through it) and the boards (nodes, linked ones included). The scope of "review
     * the cards under this page".
     */
    public Set<UUID> below(UUID pageId) {
        Map<UUID, List<UUID>> byParent = new HashMap<>();
        for (NotebookPage page : pages.values()) {
            if (page.getParentId() != null) {
                byParent.computeIfAbsent(page.getParentId(), k -> new ArrayList<>()).add(page.getId());
            }
        }
        Set<UUID> seen = new HashSet<>();
        List<UUID> stack = new ArrayList<>(List.of(pageId));
        while (!stack.isEmpty()) {
            UUID current = stack.remove(stack.size() - 1);
            if (!seen.add(current)) continue;
            stack.addAll(byParent.getOrDefault(current, List.of()));
            stack.addAll(childrenOf(current));
        }
        return seen;
    }

    /**
     * The leaf to suggest next under a page: the first leaf being studied, else the first not
     * started, in reading order. Null when everything is done.
     */
    public UUID nextLeaf(UUID pageId) {
        UUID[] firstStudying = new UUID[1];
        UUID[] firstToStudy = new UUID[1];
        walkLeaves(pageId, new HashSet<>(), firstStudying, firstToStudy);
        return firstStudying[0] != null ? firstStudying[0] : firstToStudy[0];
    }

    private void walkLeaves(UUID pageId, Set<UUID> visited, UUID[] studying, UUID[] toStudy) {
        if (!visited.add(pageId) || studying[0] != null) return;
        List<UUID> kids = childrenOf(pageId);
        if (kids.isEmpty()) {
            NotebookPage page = pages.get(pageId);
            if (page == null) return;
            if (page.getStatus() == NotebookStatus.STUDYING) studying[0] = pageId;
            else if (page.getStatus() == NotebookStatus.TO_STUDY && toStudy[0] == null) toStudy[0] = pageId;
            return;
        }
        for (UUID child : kids) {
            walkLeaves(child, visited, studying, toStudy);
        }
    }
}

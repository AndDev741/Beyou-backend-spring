package beyou.beyouapp.backend.domain.notebook.cycle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.NotebookPage;

/**
 * Where each page of one user lives: its home parent ({@code parentId}), up to the topic.
 *
 * <p>A cycle's subject is any page, and every question "does this page belong to that subject"
 * is answered here, by the home chain only. The board relation is the wrong one to ask: a linked
 * node shows a page from another topic on this board, and if it counted, one pomodoro on that
 * page would belong to two subjects (its home's and the board's) and credit two blocks. The home
 * chain gives every page exactly one line of ancestors, so with subjects that never overlap
 * (StudyCycleService refuses the ones that would) a page has at most one subject.
 *
 * <p>Every walk keeps a visited set. The tree has no loops today, and the set is what keeps one
 * that got in some other way from hanging a request.
 */
final class HomeTree {

    /** Page id to its home parent; null for a topic. Every page of the user is a key. */
    private final Map<UUID, UUID> parentOf;

    private HomeTree(Map<UUID, UUID> parentOf) {
        this.parentOf = parentOf;
    }

    static HomeTree ofPages(Collection<NotebookPage> pages) {
        Map<UUID, UUID> parentOf = new HashMap<>();
        for (NotebookPage page : pages) {
            parentOf.put(page.getId(), page.getParentId());
        }
        return new HomeTree(parentOf);
    }

    /** From {@code NotebookPageRepository#findHomeLinks} rows: [id, parentId]. */
    static HomeTree ofLinks(Collection<Object[]> links) {
        Map<UUID, UUID> parentOf = new HashMap<>();
        for (Object[] link : links) {
            parentOf.put((UUID) link[0], (UUID) link[1]);
        }
        return new HomeTree(parentOf);
    }

    /** The page, its parent, and so on up to its topic. Empty for a page the user does not have. */
    List<UUID> chain(UUID pageId) {
        List<UUID> chain = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        UUID current = pageId;
        while (current != null && parentOf.containsKey(current) && visited.add(current)) {
            chain.add(current);
            current = parentOf.get(current);
        }
        return chain;
    }

    /**
     * The subject a page counts under: the nearest of {@code subjects} on its home chain, itself
     * included, or null when none is. Nearest rather than any, so that subjects which came to
     * overlap after they were chosen (a page moved under another subject) still give one answer.
     */
    UUID nearest(UUID pageId, Set<UUID> subjects) {
        if (pageId == null || subjects.isEmpty()) return null;
        for (UUID id : chain(pageId)) {
            if (subjects.contains(id)) return id;
        }
        return null;
    }

    /** Whether {@code pageId} is {@code subjectId} or lives somewhere below it. */
    boolean within(UUID pageId, UUID subjectId) {
        return chain(pageId).contains(subjectId);
    }
}

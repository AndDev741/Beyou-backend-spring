package beyou.beyouapp.backend.domain.notebook.board;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * The order a board's page nodes are studied in. Everything the server lists from a board uses
 * it: the sidebar tree, the "next" suggestion, the assistant's board edits. The web board numbers
 * and tidies its nodes with the same algorithm (pathOrder in boardLayout.ts), so the sidebar and
 * the board agree. Change one and change the other.
 *
 * <p>Every node comes after the nodes that point to it. Where the edges leave a choice (two
 * branches, or nodes with no edges at all), the node that comes first where the person put it
 * goes first: row by row, each row left to right. Rows are bucketed by the grid's row height, so
 * a node nudged a few pixels up still reads as part of its row. A loop of edges is broken at its
 * first node in that order, so every node still gets a place.
 */
public final class BoardPath {

    /** An edge, cut down to what the order needs: node ids, not page ids. */
    public record Arrow(UUID source, UUID target) {
    }

    private BoardPath() {
    }

    /**
     * {@code nodes} in path order. The caller passes the page nodes of one board and that board's
     * edges; an edge to a node not in {@code nodes} is ignored.
     */
    public static <N> List<N> order(Collection<N> nodes, Function<N, UUID> id, ToDoubleFunction<N> x,
            ToDoubleFunction<N> y, Collection<Arrow> arrows) {
        List<N> pages = nodes.stream()
                .sorted(Comparator.comparingLong((N n) -> Math.round(y.applyAsDouble(n) / NotebookBoardService.ROW_STEP))
                        .thenComparingDouble(x))
                .toList();
        Set<UUID> ids = new HashSet<>();
        for (N node : pages) {
            ids.add(id.apply(node));
        }
        Map<UUID, Integer> incoming = new HashMap<>();
        Map<UUID, List<UUID>> targets = new HashMap<>();
        for (Arrow arrow : arrows) {
            if (!ids.contains(arrow.source()) || !ids.contains(arrow.target()) || arrow.source().equals(arrow.target())) {
                continue;
            }
            incoming.merge(arrow.target(), 1, Integer::sum);
            targets.computeIfAbsent(arrow.source(), k -> new ArrayList<>()).add(arrow.target());
        }
        List<N> ordered = new ArrayList<>(pages.size());
        Set<UUID> placed = new HashSet<>();
        while (ordered.size() < pages.size()) {
            N next = pages.stream()
                    .filter(n -> !placed.contains(id.apply(n)) && incoming.getOrDefault(id.apply(n), 0) == 0)
                    .findFirst()
                    .orElseGet(() -> pages.stream().filter(n -> !placed.contains(id.apply(n))).findFirst().orElseThrow());
            placed.add(id.apply(next));
            ordered.add(next);
            for (UUID target : targets.getOrDefault(id.apply(next), List.of())) {
                incoming.merge(target, -1, Integer::sum);
            }
        }
        return ordered;
    }
}
